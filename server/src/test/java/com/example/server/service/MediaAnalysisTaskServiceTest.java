package com.example.server.service;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.AnalysisRedisKeys;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaAnalysisTaskServiceTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef";

    @Mock
    private MediaFileMapper mediaFileMapper;
    @Mock
    private MediaService mediaService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private RocketMQTemplate rocketMQTemplate;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock contentHashLock;
    @Mock
    private RRateLimiter rateLimiter;

    private MediaAnalysisTaskService service;

    @BeforeEach
    void setUp() {
        service = new MediaAnalysisTaskService();
        ReflectionTestUtils.setField(service, "mediaFileMapper", mediaFileMapper);
        ReflectionTestUtils.setField(service, "mediaService", mediaService);
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "rocketMQTemplate", rocketMQTemplate);
        ReflectionTestUtils.setField(service, "redissonClient", redissonClient);
    }

    @Test
    void redisHitUsesCachedMd5WithoutRecalculation() throws Exception {
        stubRedisOperations();
        MediaFile file = media(1L, HASH);
        when(valueOperations.get(AnalysisRedisKeys.contentHash(1L))).thenReturn(HASH);

        assertEquals(HASH, service.resolveContentHash(file));

        verify(mediaService, never()).calculateStoredContentHash(any());
        verify(mediaService, never()).rememberContentHash(any(), any());
    }

    @Test
    void redisMissUsesMysqlAndRepopulatesCache() throws Exception {
        stubRedisOperations();
        MediaFile file = media(2L, HASH);
        when(valueOperations.get(AnalysisRedisKeys.contentHash(2L))).thenReturn(null);

        assertEquals(HASH, service.resolveContentHash(file));

        verify(mediaService).rememberContentHash(2L, HASH);
        verify(mediaService, never()).calculateStoredContentHash(any());
    }

    @Test
    void historicalRowIsCalculatedThenPersistedBeforeCaching() throws Exception {
        stubRedisOperations();
        MediaFile file = media(3L, null);
        when(valueOperations.get(AnalysisRedisKeys.contentHash(3L))).thenReturn(null);
        when(redissonClient.getLock(AnalysisRedisKeys.contentHashLock(3L))).thenReturn(contentHashLock);
        when(contentHashLock.isHeldByCurrentThread()).thenReturn(true);
        when(mediaFileMapper.selectById(3L)).thenReturn(file);
        when(mediaService.calculateStoredContentHash(file)).thenReturn(HASH);
        when(mediaFileMapper.updateById(any(MediaFile.class))).thenReturn(1);

        assertEquals(HASH, service.resolveContentHash(file));

        ArgumentCaptor<MediaFile> patch = ArgumentCaptor.forClass(MediaFile.class);
        InOrder persistenceOrder = inOrder(mediaFileMapper, mediaService);
        persistenceOrder.verify(mediaFileMapper).updateById(patch.capture());
        persistenceOrder.verify(mediaService).rememberContentHash(3L, HASH);
        assertEquals(3L, patch.getValue().getId());
        assertEquals(HASH, patch.getValue().getContentHash());
        verify(contentHashLock).lock();
        verify(contentHashLock).unlock();
    }

    @Test
    void inaccessibleHistoricalRowUsesExplicitNonPersistentFallback() throws Exception {
        stubRedisOperations();
        MediaFile file = media(4L, null);
        when(valueOperations.get(AnalysisRedisKeys.contentHash(4L))).thenReturn(null);
        when(redissonClient.getLock(AnalysisRedisKeys.contentHashLock(4L))).thenReturn(contentHashLock);
        when(contentHashLock.isHeldByCurrentThread()).thenReturn(true);
        when(mediaFileMapper.selectById(4L)).thenReturn(file);
        when(mediaService.calculateStoredContentHash(file)).thenThrow(new IOException("not accessible"));

        assertEquals("legacy-media:4", service.resolveContentHash(file));

        verify(mediaFileMapper, never()).updateById(any(MediaFile.class));
        verify(mediaService, never()).rememberContentHash(any(), any());
    }

    @Test
    void failedMysqlBackfillRemovesExistingRedisOnlyHash() {
        stubRedisOperations();
        MediaFile file = media(5L, null);
        when(valueOperations.get(AnalysisRedisKeys.contentHash(5L))).thenReturn(HASH);
        when(mediaFileMapper.updateById(any(MediaFile.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> service.resolveContentHash(file));

        verify(mediaService).forgetContentHash(5L);
        verify(mediaService, never()).rememberContentHash(any(), any());
    }

    @Test
    void activeKeyContainsUserId() {
        assertEquals("analysis:active:7:" + HASH, AnalysisRedisKeys.active(7L, HASH));
        assertEquals("lock:analysis:7:" + HASH, AnalysisRedisKeys.analysisLock(7L, HASH));
    }

    @Test
    void sameContentHashForDifferentUsersProducesDifferentKeys() {
        assertNotEquals(
                AnalysisRedisKeys.active(7L, HASH),
                AnalysisRedisKeys.active(8L, HASH));
    }

    @Test
    void mqSendFailureDeletesUserScopedActiveKeyAndDoesNotReturnSubmitted() {
        stubRedisOperations();
        MediaFile file = media(6L, HASH);
        String activeKey = AnalysisRedisKeys.active(7L, HASH);
        when(mediaFileMapper.selectById(6L)).thenReturn(file);
        when(redissonClient.getRateLimiter("limit:ai:global")).thenReturn(rateLimiter);
        when(rateLimiter.tryAcquire(1)).thenReturn(true);
        when(valueOperations.get(AnalysisRedisKeys.contentHash(6L))).thenReturn(HASH);
        when(valueOperations.setIfAbsent(activeKey, "6", 2, java.util.concurrent.TimeUnit.HOURS))
                .thenReturn(true);
        doThrow(new RuntimeException("broker unavailable"))
                .when(rocketMQTemplate)
                .convertAndSend(eq("video-analysis-topic"), any(com.example.server.dto.AnalysisTaskMsg.class));

        assertThrows(IllegalStateException.class,
                () -> service.submitAnalysis(6L, 7L, null));

        verify(redisTemplate).delete(activeKey);
    }

    @Test
    void analysisKeysRejectMissingUserId() {
        assertThrows(IllegalArgumentException.class,
                () -> AnalysisRedisKeys.active(null, HASH));
        assertThrows(IllegalArgumentException.class,
                () -> AnalysisRedisKeys.analysisLock(null, HASH));
    }

    private void stubRedisOperations() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private MediaFile media(Long id, String contentHash) {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setUserId(7L);
        file.setContentHash(contentHash);
        file.setFilePath("unused-in-unit-test");
        return file;
    }
}
