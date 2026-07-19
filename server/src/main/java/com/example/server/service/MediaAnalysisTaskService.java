package com.example.server.service;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.AnalysisStatus;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.AnalysisRedisKeys;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class MediaAnalysisTaskService {

    private static final Logger log = LoggerFactory.getLogger(MediaAnalysisTaskService.class);

    private static final String DEFAULT_GOAL = "Understand the core video content and generate a structured analysis report";
    private static final String ANALYSIS_TOPIC = "video-analysis-topic";
    private static final String GLOBAL_LIMIT_KEY = "limit:ai:global";

    @Autowired
    private MediaFileMapper mediaFileMapper;

    @Autowired
    private MediaService mediaService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private AnalysisActiveKeyService activeKeyService;

    public Map<String, Object> submitAnalysis(Long mediaId, Long currentUserId, String goal) {
        return submitAnalysis(mediaId, currentUserId, goal, false);
    }

    public Map<String, Object> submitAnalysis(Long mediaId, Long currentUserId, String goal, boolean force) {
        if (mediaId == null) {
            throw new IllegalArgumentException("mediaId is required");
        }

        MediaFile file = mediaFileMapper.selectById(mediaId);
        if (file == null) {
            throw new MediaNotFoundException("media not found");
        }
        verifyOwner(file, currentUserId);

        AnalysisStatus expectedAnalysisStatus = file.getAnalysisStatus();
        String expectedAnalysisRequestId = file.getAnalysisRequestId();
        AnalysisStatus currentStatus = AnalysisStatus.effective(file.getAnalysisStatus(), file.getAiSummary());
        if (!force && currentStatus == AnalysisStatus.SUCCESS) {
            return result(file, "REUSED", "Existing analysis result reused");
        }
        if (currentStatus.isInProgress()) {
            return result(file, "RUNNING", "Analysis task is already running");
        }

        String userGoal = normalizeGoal(goal);
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(GLOBAL_LIMIT_KEY);
        rateLimiter.trySetRate(RateType.OVERALL, 10, 1, RateIntervalUnit.MINUTES);
        if (!rateLimiter.tryAcquire(1)) {
            throw new RateLimitExceededException("analysis rate limit exceeded");
        }

        String contentHash = resolveContentHash(file);
        String activeKey = AnalysisRedisKeys.active(currentUserId, contentHash);
        String analysisRequestId = UUID.randomUUID().toString();

        if (!activeKeyService.tryAcquire(activeKey, analysisRequestId, Duration.ofHours(2))) {
            return result(file, contentHash, "RUNNING", "Analysis task is already running");
        }

        int queuedRows;
        try {
            queuedRows = mediaFileMapper.queueAnalysis(
                    mediaId,
                    analysisRequestId,
                    userGoal,
                    expectedAnalysisStatus,
                    expectedAnalysisRequestId);
        } catch (RuntimeException e) {
            deleteActiveKeySafely(activeKey, analysisRequestId);
            throw new IllegalStateException("failed to persist queued analysis state", e);
        }

        if (queuedRows == 0) {
            deleteActiveKeySafely(activeKey, analysisRequestId);
            return resolveQueueConflict(mediaId, currentUserId);
        }

        try {
            clearMediaListCache(file);

            AnalysisTaskMsg msg = new AnalysisTaskMsg(
                    mediaId, currentUserId, "START_ANALYSIS", contentHash, userGoal, analysisRequestId);
            rocketMQTemplate.convertAndSend(ANALYSIS_TOPIC, msg);

            Map<String, Object> result = result(
                    file, contentHash, "SUBMITTED", "Analysis task submitted");
            result.put("analysisRequestId", analysisRequestId);
            return result;
        } catch (Exception e) {
            deleteActiveKeySafely(activeKey, analysisRequestId);
            try {
                int failedRows = mediaFileMapper.markSubmitFailed(
                        mediaId, analysisRequestId, conciseError(e), LocalDateTime.now());
                if (failedRows == 0) {
                    log.info("Analysis request {} advanced beyond QUEUED; submit failure did not overwrite it",
                            analysisRequestId);
                } else {
                    clearMediaListCache(file);
                }
            } catch (RuntimeException statusError) {
                log.error("Failed to mark queued analysis request {} as FAILED", analysisRequestId, statusError);
            }
            throw new IllegalStateException("failed to submit analysis task", e);
        }
    }

    private Map<String, Object> resolveQueueConflict(Long mediaId, Long currentUserId) {
        MediaFile latest = mediaFileMapper.selectById(mediaId);
        if (latest == null) {
            throw new MediaNotFoundException("media disappeared while submitting analysis");
        }
        verifyOwner(latest, currentUserId);

        AnalysisStatus latestStatus = AnalysisStatus.effective(
                latest.getAnalysisStatus(), latest.getAiSummary());
        if (latestStatus == AnalysisStatus.SUCCESS) {
            return result(latest, "REUSED", "Analysis completed while this request was being submitted");
        }
        if (latestStatus.isInProgress()) {
            return result(latest, "RUNNING", "Another analysis request is already running");
        }
        throw new AnalysisStateConflictException(
                "analysis state changed to " + latestStatus + " and cannot be safely queued");
    }

    private Map<String, Object> result(MediaFile file, String status, String message) {
        return result(file, file.getContentHash(), status, message);
    }

    private Map<String, Object> result(MediaFile file, String contentHash, String status, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("mediaId", file.getId());
        if (contentHash != null) {
            result.put("contentHash", contentHash);
        }
        if (file.getAnalysisRequestId() != null) {
            result.put("analysisRequestId", file.getAnalysisRequestId());
        }
        result.put("status", status);
        result.put("message", message);
        return result;
    }

    private void deleteActiveKeySafely(String activeKey, String analysisRequestId) {
        try {
            activeKeyService.deleteIfOwned(activeKey, analysisRequestId);
        } catch (RuntimeException cleanupError) {
            log.error("Failed to delete owned analysis activeKey {}", activeKey, cleanupError);
        }
    }

    static String conciseError(Throwable error) {
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

    private String normalizeGoal(String goal) {
        String userGoal = goal == null || goal.isBlank() ? DEFAULT_GOAL : goal.trim();
        if (userGoal.length() > 500) {
            throw new IllegalArgumentException("goal must not exceed 500 characters");
        }
        return userGoal;
    }

    private void verifyOwner(MediaFile file, Long currentUserId) {
        Long ownerId = file.getUserId();
        if (currentUserId == null || ownerId == null || !ownerId.equals(currentUserId)) {
            throw new AccessDeniedException("no permission to analyze this media");
        }
    }

    String resolveContentHash(MediaFile file) {
        String redisHash = redisTemplate.opsForValue().get(AnalysisRedisKeys.contentHash(file.getId()));
        String databaseHash = file.getContentHash();

        if (AnalysisRedisKeys.isMd5(redisHash)) {
            if (AnalysisRedisKeys.isMd5(databaseHash) && !databaseHash.equals(redisHash)) {
                log.warn("contentHash cache mismatch for mediaId={}; using MySQL value", file.getId());
                mediaService.rememberContentHash(file.getId(), databaseHash);
                return databaseHash;
            }
            if (!AnalysisRedisKeys.isMd5(databaseHash)) {
                persistContentHash(file, redisHash);
                mediaService.rememberContentHash(file.getId(), redisHash);
            }
            return redisHash;
        }

        if (AnalysisRedisKeys.isMd5(databaseHash)) {
            mediaService.rememberContentHash(file.getId(), databaseHash);
            return databaseHash;
        }
        if (databaseHash != null && !databaseHash.isBlank()) {
            log.warn("Ignoring invalid MySQL contentHash for mediaId={}", file.getId());
        }

        return calculateAndPersistHistoricalHash(file);
    }

    private String calculateAndPersistHistoricalHash(MediaFile originalFile) {
        RLock lock = redissonClient.getLock(AnalysisRedisKeys.contentHashLock(originalFile.getId()));
        lock.lock();
        try {
            MediaFile latest = mediaFileMapper.selectById(originalFile.getId());
            if (latest == null) {
                throw new MediaNotFoundException("media not found while resolving contentHash");
            }

            String redisHash = redisTemplate.opsForValue()
                    .get(AnalysisRedisKeys.contentHash(latest.getId()));
            String databaseHash = latest.getContentHash();
            if (AnalysisRedisKeys.isMd5(redisHash)) {
                if (AnalysisRedisKeys.isMd5(databaseHash) && !databaseHash.equals(redisHash)) {
                    log.warn("contentHash cache mismatch for mediaId={}; using MySQL value", latest.getId());
                    mediaService.rememberContentHash(latest.getId(), databaseHash);
                    return databaseHash;
                }
                if (!AnalysisRedisKeys.isMd5(databaseHash)) {
                    persistContentHash(latest, redisHash);
                    mediaService.rememberContentHash(latest.getId(), redisHash);
                }
                return redisHash;
            }
            if (AnalysisRedisKeys.isMd5(databaseHash)) {
                mediaService.rememberContentHash(latest.getId(), databaseHash);
                return databaseHash;
            }

            String calculatedHash;
            try {
                calculatedHash = mediaService.calculateStoredContentHash(latest);
            } catch (Exception e) {
                String fallback = AnalysisRedisKeys.legacyContentHash(latest.getId());
                log.warn("Unable to calculate historical contentHash for mediaId={}; "
                                + "using non-persistent fallback {}. Reason: {}",
                        latest.getId(), fallback, e.getMessage());
                return fallback;
            }

            if (!AnalysisRedisKeys.isMd5(calculatedHash)) {
                throw new IllegalStateException("calculated contentHash is not a valid MD5");
            }
            persistContentHash(latest, calculatedHash);
            mediaService.rememberContentHash(latest.getId(), calculatedHash);
            log.info("Backfilled contentHash for historical mediaId={}", latest.getId());
            return calculatedHash;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void persistContentHash(MediaFile file, String contentHash) {
        if (!AnalysisRedisKeys.isMd5(contentHash)) {
            throw new IllegalArgumentException("only a valid MD5 may be persisted as contentHash");
        }
        MediaFile patch = new MediaFile();
        patch.setId(file.getId());
        patch.setContentHash(contentHash);
        try {
            if (mediaFileMapper.updateById(patch) != 1) {
                throw new IllegalStateException("failed to persist contentHash for mediaId=" + file.getId());
            }
        } catch (RuntimeException e) {
            mediaService.forgetContentHash(file.getId());
            throw e;
        }
        file.setContentHash(contentHash);
    }

    private void clearMediaListCache(MediaFile file) {
        String userIdKey = file.getUserId() == null ? "anon" : String.valueOf(file.getUserId());
        redisTemplate.delete("media:list:user:" + userIdKey);
    }

    public static class MediaNotFoundException extends RuntimeException {
        public MediaNotFoundException(String message) {
            super(message);
        }
    }

    public static class AccessDeniedException extends RuntimeException {
        public AccessDeniedException(String message) {
            super(message);
        }
    }

    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String message) {
            super(message);
        }
    }

    public static class AnalysisStateConflictException extends RuntimeException {
        public AnalysisStateConflictException(String message) {
            super(message);
        }
    }
}
