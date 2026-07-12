package com.example.server.service;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class MediaAnalysisTaskService {

    private static final String DEFAULT_GOAL = "Understand the core video content and generate a structured analysis report";
    private static final String ANALYSIS_TOPIC = "video-analysis-topic";
    private static final String ACTIVE_KEY_PREFIX = "analysis:active:";
    private static final String MEDIA_MD5_KEY_PREFIX = "media:md5:";
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
        String activeKey = ACTIVE_KEY_PREFIX + contentHash;
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

            AnalysisTaskMsg msg = new AnalysisTaskMsg(mediaId, "START_ANALYSIS", contentHash, userGoal);
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
        if (ownerId == null) {
            return;
        }
        if (currentUserId == null || !ownerId.equals(currentUserId)) {
            throw new AccessDeniedException("no permission to analyze this media");
        }
    }

    private String resolveContentHash(MediaFile file) {
        String redisKey = MEDIA_MD5_KEY_PREFIX + file.getId();
        String contentHash = redisTemplate.opsForValue().get(redisKey);
        if (isMd5(contentHash)) {
            return contentHash;
        }

        String filePath = file.getFilePath();
        if (filePath != null && !filePath.startsWith("http")) {
            File localFile = new File(filePath);
            if (localFile.isFile()) {
                try {
                    contentHash = mediaService.calculateMd5(localFile);
                    mediaService.rememberContentHash(file.getId(), contentHash);
                    return contentHash;
                } catch (Exception ignored) {
                }
            }
        }

        return "media-" + file.getId();
    }

    private boolean isMd5(String value) {
        return value != null && value.matches("[a-f0-9]{32}");
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
