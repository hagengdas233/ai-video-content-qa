package com.example.server.service;

import com.example.server.utils.AnalysisRedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    private static final String HASH = "abcdef0123456789abcdef0123456789";

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private MediaService mediaService;

    @BeforeEach
    void setUp() {
        mediaService = new MediaService();
        ReflectionTestUtils.setField(mediaService, "redisTemplate", redisTemplate);
    }

    @Test
    void contentHashCacheUsesConfiguredTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        mediaService.rememberContentHash(9L, HASH);

        verify(valueOperations).set(
                AnalysisRedisKeys.contentHash(9L), HASH,
                MediaService.CONTENT_HASH_CACHE_HOURS, TimeUnit.HOURS);
    }

    @Test
    void nonMd5ValueIsNeverCachedAsContentHash() {
        assertThrows(IllegalArgumentException.class,
                () -> mediaService.rememberContentHash(9L, "legacy-media:9"));

        verify(redisTemplate, never()).opsForValue();
    }
}
