package com.example.server.service;

import com.example.server.dto.AnalysisTaskMsg;
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
import java.util.concurrent.TimeUnit;

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

    public Map<String, Object> submitAnalysis(Long mediaId, Long currentUserId, String goal) {
        if (mediaId == null) {
            throw new IllegalArgumentException("mediaId is required");
        }

        String userGoal = normalizeGoal(goal);
        MediaFile file = mediaFileMapper.selectById(mediaId);
        if (file == null) {
            throw new MediaNotFoundException("media not found");
        }
        verifyOwner(file, currentUserId);

        RRateLimiter rateLimiter = redissonClient.getRateLimiter(GLOBAL_LIMIT_KEY);
        rateLimiter.trySetRate(RateType.OVERALL, 10, 1, RateIntervalUnit.MINUTES);
        if (!rateLimiter.tryAcquire(1)) {
            throw new RateLimitExceededException("analysis rate limit exceeded");
        }

        String contentHash = resolveContentHash(file);
        String activeKey = AnalysisRedisKeys.active(currentUserId, contentHash);
        Boolean accepted = redisTemplate.opsForValue()
                .setIfAbsent(activeKey, String.valueOf(mediaId), 2, TimeUnit.HOURS);

        Map<String, Object> result = new HashMap<>();
        result.put("mediaId", mediaId);
        result.put("contentHash", contentHash);

        if (!Boolean.TRUE.equals(accepted)) {
            result.put("status", "RUNNING");
            result.put("message", "Analysis task is already running");
            return result;
        }

        try {
            file.setAiSummary("[MQ] Analysis task queued");
            mediaFileMapper.updateById(file);
            clearMediaListCache(file);

            AnalysisTaskMsg msg = new AnalysisTaskMsg(
                    mediaId, currentUserId, "START_ANALYSIS", contentHash, userGoal);
            rocketMQTemplate.convertAndSend(ANALYSIS_TOPIC, msg);

            result.put("status", "SUBMITTED");
            result.put("message", "Analysis task submitted");
            return result;
        } catch (Exception e) {
            redisTemplate.delete(activeKey);
            throw new IllegalStateException("failed to submit analysis task", e);
        }
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
}
