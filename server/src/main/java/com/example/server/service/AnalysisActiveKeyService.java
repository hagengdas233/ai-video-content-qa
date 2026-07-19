package com.example.server.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;

@Service
public class AnalysisActiveKeyService {

    private static final DefaultRedisScript<Long> DELETE_IF_OWNED = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] "
                    + "then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public AnalysisActiveKeyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryAcquire(String activeKey, String analysisRequestId, Duration ttl) {
        Boolean accepted = redisTemplate.opsForValue()
                .setIfAbsent(activeKey, analysisRequestId, ttl);
        return Boolean.TRUE.equals(accepted);
    }

    public boolean deleteIfOwned(String activeKey, String analysisRequestId) {
        Long deleted = redisTemplate.execute(
                DELETE_IF_OWNED, Collections.singletonList(activeKey), analysisRequestId);
        return Long.valueOf(1L).equals(deleted);
    }
}
