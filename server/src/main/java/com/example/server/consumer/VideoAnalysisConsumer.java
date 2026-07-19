package com.example.server.consumer;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.dto.AiAnalysisOutput;
import com.example.server.entity.AnalysisStatus;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AnalysisActiveKeyService;
import com.example.server.service.AiService;
import com.example.server.utils.AnalysisRedisKeys;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Component
//监听 "video-analysis-topic" 主题，组名随便起
@RocketMQMessageListener(topic = "video-analysis-topic", consumerGroup = "video-group")
public class VideoAnalysisConsumer implements RocketMQListener<AnalysisTaskMsg> {

    private static final Logger log = LoggerFactory.getLogger(VideoAnalysisConsumer.class);

    @Autowired
    private AiService aiService;

    @Autowired
    private MediaFileMapper mediaFileMapper;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private AnalysisActiveKeyService activeKeyService;

    @Override
    public void onMessage(AnalysisTaskMsg msg) {
        if (msg == null) {
            log.warn("Discarding null analysis MQ message");
            return;
        }
        Long mediaId = msg.getMediaId();
        if (mediaId == null || mediaId <= 0) {
            log.warn("Discarding analysis MQ message without a valid mediaId");
            return;
        }
        Long userId = msg.getUserId();
        if (userId == null || userId <= 0) {
            log.warn("Discarding legacy analysis MQ message without a valid userId, mediaId={}", mediaId);
            return;
        }
        String contentHash = msg.getContentHash();
        if (!AnalysisRedisKeys.isSupportedContentHash(mediaId, contentHash)) {
            log.warn("Discarding analysis MQ message with invalid contentHash, mediaId={}", mediaId);
            return;
        }
        String analysisRequestId = msg.getAnalysisRequestId();
        if (!isUuid(analysisRequestId)) {
            log.warn("Discarding analysis MQ message without a valid analysisRequestId, mediaId={}", mediaId);
            return;
        }
        String lockKey = AnalysisRedisKeys.analysisLock(userId, contentHash);
        String activeKey = AnalysisRedisKeys.active(userId, contentHash);
        log.info("Received analysis request mediaId={}, requestId={}", mediaId, analysisRequestId);

        // RocketMQ invokes this method on its consumer thread. Keep processing synchronous so
        // the listener only acknowledges the message after analysis state is durably finalized.
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        boolean currentRequest = false;
        try {
            acquired = lock.tryLock(0, -1, TimeUnit.SECONDS);
            if (!acquired) {
                log.info("Analysis lock is already held; skipping mediaId={}, requestId={}",
                        mediaId, analysisRequestId);
                return;
            }

            MediaFile file = mediaFileMapper.selectById(mediaId);
            if (file == null || !Objects.equals(file.getUserId(), userId)) {
                log.warn("Discarding analysis message for missing or mismatched mediaId={}", mediaId);
                return;
            }
            if (!Objects.equals(file.getAnalysisRequestId(), analysisRequestId)) {
                log.info("Discarding stale analysis message mediaId={}, messageRequestId={}, currentRequestId={}",
                        mediaId, analysisRequestId, file.getAnalysisRequestId());
                return;
            }

            currentRequest = true;
            AnalysisStatus status = AnalysisStatus.effective(file.getAnalysisStatus(), file.getAiSummary());
            if (status == AnalysisStatus.SUCCESS) {
                log.info("Analysis request already succeeded; acknowledging duplicate mediaId={}, requestId={}",
                        mediaId, analysisRequestId);
                return;
            }
            if (!status.isInProgress()) {
                log.info("Ignoring analysis request in terminal/non-runnable state {}: mediaId={}, requestId={}",
                        status, mediaId, analysisRequestId);
                return;
            }

            if (mediaFileMapper.markAnalysisRunning(
                    mediaId, analysisRequestId, LocalDateTime.now()) != 1) {
                log.info("Analysis request changed before RUNNING transition: mediaId={}, requestId={}",
                        mediaId, analysisRequestId);
                return;
            }
            clearMediaListCache(userId);

            AiAnalysisOutput output = aiService.analyze(mediaId, msg.getUserGoal());
            if (mediaFileMapper.markAnalysisSuccess(
                    mediaId,
                    analysisRequestId,
                    output.transcriptText(),
                    output.aiSummary(),
                    LocalDateTime.now()) == 1) {
                clearMediaListCache(userId);
                log.info("Analysis request completed mediaId={}, requestId={}", mediaId, analysisRequestId);
            } else {
                log.warn("Analysis result was not persisted because request is no longer current: "
                        + "mediaId={}, requestId={}", mediaId, analysisRequestId);
            }
        } catch (Exception e) {
            log.error("Analysis request failed mediaId={}, requestId={}", mediaId, analysisRequestId, e);
            if (acquired && currentRequest) {
                mediaFileMapper.markExecutionFailed(
                        mediaId, analysisRequestId, conciseError(e), LocalDateTime.now());
                clearMediaListCache(userId);
            }
        } finally {
            if (acquired) {
                try {
                    activeKeyService.deleteIfOwned(activeKey, analysisRequestId);
                } catch (RuntimeException cleanupError) {
                    log.error("Failed to delete owned analysis activeKey {}", activeKey, cleanupError);
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            }
        }
    }

    private void clearMediaListCache(Long userId) {
        redisTemplate.delete("media:list:user:" + userId);
    }

    private static boolean isUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String conciseError(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        message = message.replace('\r', ' ').replace('\n', ' ').trim();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
