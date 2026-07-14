package com.example.server.consumer;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Component
//监听 "video-analysis-topic" 主题，组名随便起
@RocketMQMessageListener(topic = "video-analysis-topic", consumerGroup = "video-group")
public class VideoAnalysisConsumer implements RocketMQListener<AnalysisTaskMsg> {

    private static final Logger log = LoggerFactory.getLogger(VideoAnalysisConsumer.class);

    @Autowired
    private AiService aiService;

    @Autowired
    private MediaFileMapper mediaFileMapper;

    //注入之前配置好的 IO 密集型线程池
    @Autowired
    private Executor aiTaskExecutor;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

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
        String lockKey = AnalysisRedisKeys.analysisLock(userId, contentHash);
        String activeKey = AnalysisRedisKeys.active(userId, contentHash);
        System.out.println("⚡ [MQ消费者] 收到任务 ID: " + mediaId + "，准备派发给线程池...");

        //CompletableFuture异步编排
        //即使MQ消费者线程很快，我们也不阻塞它，而是把重活扔给业务线程池
        CompletableFuture.runAsync(() -> {
            System.out.println("🧵 [线程池] 开始执行 DeepSeek 分析逻辑...");
            RLock lock = redissonClient.getLock(lockKey);
            boolean acquired = false;
            try {
                acquired = lock.tryLock(0, -1, TimeUnit.SECONDS);
                if (!acquired) {
                    System.out.println("相同视频正在处理中，跳过重复消息: " + mediaId);
                    return;
                }
                aiService.asyncAnalyze(mediaId, msg.getUserGoal());
            } catch (Exception e) {
                System.err.println("❌ 任务执行失败: " + e.getMessage());
                markAsFailed(mediaId, e.getMessage());
            } finally {
                if (acquired) {
                    try {
                        redisTemplate.delete(activeKey);
                    } catch (RuntimeException cleanupError) {
                        log.error("Failed to delete analysis activeKey {}", activeKey, cleanupError);
                    } finally {
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                    }
                }
            }
        }, aiTaskExecutor);
    }

    private void markAsFailed(Long id, String error) {
        MediaFile file = mediaFileMapper.selectById(id);
        if (file != null) {
            file.setAiSummary("❌ 分析失败: " + error);
            mediaFileMapper.updateById(file);
        }
    }
}
