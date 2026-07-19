package com.example.server.consumer;

import com.example.server.dto.AiAnalysisOutput;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.AnalysisStatus;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AiService;
import com.example.server.service.AnalysisActiveKeyService;
import com.example.server.utils.AnalysisRedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoAnalysisConsumerTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef";
    private static final String REQUEST_ID = "11111111-1111-4111-8111-111111111111";
    private static final String NEW_REQUEST_ID = "22222222-2222-4222-8222-222222222222";

    @Mock
    private AiService aiService;
    @Mock
    private MediaFileMapper mediaFileMapper;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private AnalysisActiveKeyService activeKeyService;
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
        ReflectionTestUtils.setField(consumer, "activeKeyService", activeKeyService);
    }

    @Test
    void onMessageDoesNotReturnUntilAiAndSuccessPersistenceComplete() throws Exception {
        MediaFile file = media(AnalysisStatus.QUEUED, REQUEST_ID, "## old result");
        stubOwnedLock();
        when(mediaFileMapper.selectById(1L)).thenReturn(file);
        when(mediaFileMapper.markAnalysisRunning(eq(1L), eq(REQUEST_ID), any(LocalDateTime.class)))
                .thenReturn(1);
        when(aiService.analyze(1L, "goal"))
                .thenReturn(new AiAnalysisOutput("new transcript", "## new result"));
        when(mediaFileMapper.markAnalysisSuccess(
                eq(1L), eq(REQUEST_ID), eq("new transcript"), eq("## new result"), any(LocalDateTime.class)))
                .thenReturn(1);

        consumer.onMessage(message(REQUEST_ID));

        InOrder completionOrder = inOrder(aiService, mediaFileMapper);
        completionOrder.verify(aiService).analyze(1L, "goal");
        completionOrder.verify(mediaFileMapper).markAnalysisSuccess(
                eq(1L), eq(REQUEST_ID), eq("new transcript"), eq("## new result"), any(LocalDateTime.class));
        verify(activeKeyService).deleteIfOwned(AnalysisRedisKeys.active(7L, HASH), REQUEST_ID);
        verify(lock).unlock();
        assertEquals("## old result", file.getAiSummary());
    }

    @Test
    void duplicateMessageForAlreadySuccessfulRequestDoesNotRunAi() throws Exception {
        MediaFile file = media(AnalysisStatus.SUCCESS, REQUEST_ID, "## result");
        stubOwnedLock();
        when(mediaFileMapper.selectById(1L)).thenReturn(file);

        consumer.onMessage(message(REQUEST_ID));

        verify(aiService, never()).analyze(any(), any());
        verify(mediaFileMapper, never()).markAnalysisRunning(any(), any(), any());
        verify(mediaFileMapper, never()).markAnalysisSuccess(any(), any(), any(), any(), any());
        verify(activeKeyService).deleteIfOwned(AnalysisRedisKeys.active(7L, HASH), REQUEST_ID);
    }

    @Test
    void staleMessageWithDifferentRequestIdDoesNotRunAiOrChangeCurrentState() throws Exception {
        MediaFile file = media(AnalysisStatus.QUEUED, NEW_REQUEST_ID, "## old result");
        stubOwnedLock();
        when(mediaFileMapper.selectById(1L)).thenReturn(file);

        consumer.onMessage(message(REQUEST_ID));

        verify(aiService, never()).analyze(any(), any());
        verify(mediaFileMapper, never()).markAnalysisRunning(any(), any(), any());
        verify(mediaFileMapper, never()).markAnalysisSuccess(any(), any(), any(), any(), any());
        verify(mediaFileMapper, never()).markExecutionFailed(any(), any(), any(), any());
        verify(activeKeyService).deleteIfOwned(AnalysisRedisKeys.active(7L, HASH), REQUEST_ID);
    }

    @Test
    void consumerWithoutProcessingLockDoesNotDeleteAnyActiveKey() throws Exception {
        when(redissonClient.getLock(AnalysisRedisKeys.analysisLock(7L, HASH))).thenReturn(lock);
        when(lock.tryLock(0, -1, TimeUnit.SECONDS)).thenReturn(false);

        consumer.onMessage(message(REQUEST_ID));

        verifyNoInteractions(activeKeyService, aiService, mediaFileMapper);
        verify(lock, never()).unlock();
    }

    @Test
    void analysisFailureMarksCurrentRequestFailedAndPreservesOldSummary() throws Exception {
        MediaFile file = media(AnalysisStatus.QUEUED, REQUEST_ID, "## old result");
        stubOwnedLock();
        when(mediaFileMapper.selectById(1L)).thenReturn(file);
        when(mediaFileMapper.markAnalysisRunning(eq(1L), eq(REQUEST_ID), any(LocalDateTime.class)))
                .thenReturn(1);
        doThrow(new RuntimeException("provider unavailable"))
                .when(aiService).analyze(1L, "goal");

        consumer.onMessage(message(REQUEST_ID));

        verify(mediaFileMapper).markExecutionFailed(
                eq(1L), eq(REQUEST_ID), eq("provider unavailable"), any(LocalDateTime.class));
        verify(mediaFileMapper, never()).markAnalysisSuccess(any(), any(), any(), any(), any());
        assertEquals("## old result", file.getAiSummary());
        verify(activeKeyService).deleteIfOwned(AnalysisRedisKeys.active(7L, HASH), REQUEST_ID);
    }

    @Test
    void cleanupFailureDoesNotPreventOwnedLockRelease() throws Exception {
        MediaFile file = media(AnalysisStatus.SUCCESS, REQUEST_ID, "## result");
        stubOwnedLock();
        when(mediaFileMapper.selectById(1L)).thenReturn(file);
        doThrow(new RuntimeException("redis unavailable"))
                .when(activeKeyService).deleteIfOwned(AnalysisRedisKeys.active(7L, HASH), REQUEST_ID);

        assertDoesNotThrow(() -> consumer.onMessage(message(REQUEST_ID)));

        verify(lock).isHeldByCurrentThread();
        verify(lock).unlock();
    }

    @Test
    void incompleteOrInvalidMessagesAreDiscardedBeforeLockCreation() {
        List<AnalysisTaskMsg> invalidMessages = List.of(
                message(null, 7L, HASH, REQUEST_ID),
                message(1L, null, HASH, REQUEST_ID),
                message(1L, 7L, null, REQUEST_ID),
                message(1L, 7L, "not-a-content-hash", REQUEST_ID),
                message(1L, 7L, "legacy-media:2", REQUEST_ID),
                message(1L, 7L, HASH, null),
                message(1L, 7L, HASH, "not-a-uuid"));

        invalidMessages.forEach(msg -> assertDoesNotThrow(() -> consumer.onMessage(msg)));

        verifyNoInteractions(redissonClient, activeKeyService, aiService, mediaFileMapper);
    }

    private void stubOwnedLock() throws Exception {
        when(redissonClient.getLock(AnalysisRedisKeys.analysisLock(7L, HASH))).thenReturn(lock);
        when(lock.tryLock(0, -1, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    private MediaFile media(AnalysisStatus status, String requestId, String aiSummary) {
        MediaFile file = new MediaFile();
        file.setId(1L);
        file.setUserId(7L);
        file.setContentHash(HASH);
        file.setAnalysisStatus(status);
        file.setAnalysisRequestId(requestId);
        file.setAiSummary(aiSummary);
        return file;
    }

    private AnalysisTaskMsg message(String requestId) {
        return message(1L, 7L, HASH, requestId);
    }

    private AnalysisTaskMsg message(Long mediaId, Long userId, String contentHash, String requestId) {
        return new AnalysisTaskMsg(
                mediaId, userId, "START_ANALYSIS", contentHash, "goal", requestId);
    }
}
