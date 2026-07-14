package com.example.server.consumer;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AiService;
import com.example.server.utils.AnalysisRedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoAnalysisConsumerTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef";

    @Mock
    private AiService aiService;
    @Mock
    private MediaFileMapper mediaFileMapper;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private RLock lock;

    private VideoAnalysisConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new VideoAnalysisConsumer();
        ReflectionTestUtils.setField(consumer, "aiService", aiService);
        ReflectionTestUtils.setField(consumer, "mediaFileMapper", mediaFileMapper);
        ReflectionTestUtils.setField(consumer, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(consumer, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(consumer, "aiTaskExecutor", (Executor) Runnable::run);
    }

    @Test
    void incompleteOrInvalidMessagesAreSafelyDiscardedBeforeKeyCreation() {
        List<AnalysisTaskMsg> invalidMessages = List.of(
                message(null, 7L, HASH),
                message(1L, null, HASH),
                message(1L, 7L, null),
                message(1L, 7L, "not-a-content-hash"),
                message(1L, 7L, "legacy-media:2"));

        invalidMessages.forEach(msg -> assertDoesNotThrow(() -> consumer.onMessage(msg)));

        verifyNoInteractions(redissonClient, redisTemplate, aiService, mediaFileMapper);
    }

    @Test
    void consumerWithoutProcessingLockDoesNotDeleteActiveKey() throws Exception {
        String lockKey = AnalysisRedisKeys.analysisLock(7L, HASH);
        String activeKey = AnalysisRedisKeys.active(7L, HASH);
        when(redissonClient.getLock(lockKey)).thenReturn(lock);
        when(lock.tryLock(0, -1, TimeUnit.SECONDS)).thenReturn(false);

        consumer.onMessage(message(1L, 7L, HASH));

        verify(redisTemplate, never()).delete(activeKey);
        verify(lock, never()).unlock();
        verify(aiService, never()).asyncAnalyze(1L, null);
    }

    @Test
    void redisCleanupFailureAfterAnalysisFailureDoesNotPreventOwnedLockRelease() throws Exception {
        String lockKey = AnalysisRedisKeys.analysisLock(7L, HASH);
        String activeKey = AnalysisRedisKeys.active(7L, HASH);
        when(redissonClient.getLock(lockKey)).thenReturn(lock);
        when(lock.tryLock(0, -1, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        doThrow(new RuntimeException("analysis failed"))
                .when(aiService).asyncAnalyze(1L, null);
        doThrow(new RuntimeException("redis unavailable"))
                .when(redisTemplate).delete(activeKey);

        assertDoesNotThrow(() -> consumer.onMessage(message(1L, 7L, HASH)));

        verify(redisTemplate).delete(activeKey);
        verify(lock).isHeldByCurrentThread();
        verify(lock).unlock();
    }

    private AnalysisTaskMsg message(Long mediaId, Long userId, String contentHash) {
        return new AnalysisTaskMsg(mediaId, userId, "START_ANALYSIS", contentHash, null);
    }
}
