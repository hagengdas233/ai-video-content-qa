package com.example.server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisActiveKeyServiceTest {

    private static final String KEY = "analysis:active:7:0123456789abcdef0123456789abcdef";
    private static final String REQUEST_ID = "11111111-1111-4111-8111-111111111111";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private AnalysisActiveKeyService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisActiveKeyService(redisTemplate);
    }

    @Test
    void activeKeyStoresRequestIdAsItsValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(KEY, REQUEST_ID, Duration.ofHours(2))).thenReturn(true);

        assertTrue(service.tryAcquire(KEY, REQUEST_ID, Duration.ofHours(2)));

        verify(valueOperations).setIfAbsent(KEY, REQUEST_ID, Duration.ofHours(2));
    }

    @SuppressWarnings("unchecked")
    @Test
    void deleteUsesAtomicCompareAndDeleteScript() {
        when(redisTemplate.execute(
                any(RedisScript.class), eq(Collections.singletonList(KEY)), eq(REQUEST_ID)))
                .thenReturn(1L, 0L);

        assertTrue(service.deleteIfOwned(KEY, REQUEST_ID));
        assertFalse(service.deleteIfOwned(KEY, REQUEST_ID));

        verify(redisTemplate, org.mockito.Mockito.times(2)).execute(
                any(RedisScript.class), eq(Collections.singletonList(KEY)), eq(REQUEST_ID));
    }
}
