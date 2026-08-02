package com.example.server.service;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.AnalysisMode;
import com.example.server.entity.AnalysisStatus;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.AnalysisRedisKeys;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaAnalysisTaskServiceTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_HASH = "abcdef0123456789abcdef0123456789";
    private static final String RESULT_REQUEST_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String FULL_GOAL =
            "Summarize the complete video in chronological order and generate a structured analysis report";

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
    @Mock
    private AnalysisActiveKeyService activeKeyService;

    private MediaAnalysisTaskService service;

    @BeforeEach
    void setUp() {
        service = new MediaAnalysisTaskService();
        ReflectionTestUtils.setField(service, "mediaFileMapper", mediaFileMapper);
        ReflectionTestUtils.setField(service, "mediaService", mediaService);
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "rocketMQTemplate", rocketMQTemplate);
        ReflectionTestUtils.setField(service, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(service, "activeKeyService", activeKeyService);
    }

    @Test
    void successfulAnalysisWithoutForceIsReusedWithoutRedisOrMq() {
        MediaFile file = media(1L, 7L, HASH, AnalysisStatus.SUCCESS, "## existing result");
        file.setAnalysisRequestId(UUID.randomUUID().toString());
        when(mediaFileMapper.selectById(1L)).thenReturn(file);

        Map<String, Object> result = service.submitAnalysis(1L, 7L, null, false);

        assertEquals("REUSED", result.get("status"));
        assertEquals(file.getAnalysisRequestId(), result.get("analysisRequestId"));
        assertEquals(RESULT_REQUEST_ID, result.get("resultRequestId"));
        assertEquals(AnalysisMode.FULL, result.get("resultMode"));
        verifyNoInteractions(activeKeyService, rocketMQTemplate, redissonClient);
        verify(mediaFileMapper, never()).queueAnalysis(any(), any(), any(), any(), any(), any());
    }

    @Test
    void successfulAnalysisWithForceQueuesOneRequestWithoutOverwritingOldSummary() {
        MediaFile file = media(2L, 7L, HASH, AnalysisStatus.SUCCESS, "## old result");
        when(mediaFileMapper.selectById(2L)).thenReturn(file);
        stubAcceptedSubmission(file);

        Map<String, Object> result = service.submitAnalysis(2L, 7L, "GOAL", "new goal", true);

        assertEquals("SUBMITTED", result.get("status"));
        String requestId = (String) result.get("analysisRequestId");
        assertNotNull(requestId);
        UUID.fromString(requestId);
        assertEquals("## old result", file.getAiSummary());
        assertEquals(RESULT_REQUEST_ID, result.get("resultRequestId"));
        assertEquals(AnalysisMode.FULL, result.get("resultMode"));
        assertEquals(FULL_GOAL, result.get("resultGoal"));
        verify(activeKeyService).tryAcquire(
                AnalysisRedisKeys.active(7L, HASH), requestId, Duration.ofHours(2));
        verify(mediaFileMapper).queueAnalysis(
                2L, requestId, AnalysisMode.GOAL, "new goal", AnalysisStatus.SUCCESS, null);

        ArgumentCaptor<AnalysisTaskMsg> message = ArgumentCaptor.forClass(AnalysisTaskMsg.class);
        verify(rocketMQTemplate).convertAndSend(eq("video-analysis-topic"), message.capture());
        assertEquals(requestId, message.getValue().getAnalysisRequestId());
        assertEquals(AnalysisMode.GOAL, message.getValue().getAnalysisMode());
        assertEquals("new goal", message.getValue().getUserGoal());
        assertEquals("## old result", file.getAiSummary());
    }

    @Test
    void legacyRequestWithoutModeDefaultsToFull() {
        MediaFile file = media(7L, 7L, HASH, AnalysisStatus.FAILED, null);
        file.setAnalysisMode(null);
        file.setAnalysisGoal(null);
        when(mediaFileMapper.selectById(7L)).thenReturn(file);
        stubAcceptedSubmission(file);

        Map<String, Object> result = service.submitAnalysis(7L, 7L, null, null, false);

        assertEquals("SUBMITTED", result.get("status"));
        String requestId = (String) result.get("analysisRequestId");
        verify(mediaFileMapper).queueAnalysis(
                7L, requestId, AnalysisMode.FULL, FULL_GOAL, AnalysisStatus.FAILED, null);
        ArgumentCaptor<AnalysisTaskMsg> message = ArgumentCaptor.forClass(AnalysisTaskMsg.class);
        verify(rocketMQTemplate).convertAndSend(eq("video-analysis-topic"), message.capture());
        assertEquals(AnalysisMode.FULL, message.getValue().getAnalysisMode());
        assertEquals(FULL_GOAL, message.getValue().getUserGoal());
    }

    @Test
    void goalModeRejectsBlankGoalBeforeReadingMedia() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.submitAnalysis(8L, 7L, "GOAL", "   ", false));

        assertEquals("goal is required when mode is GOAL", error.getMessage());
        verifyNoInteractions(mediaFileMapper, activeKeyService, rocketMQTemplate, redissonClient);
    }

    @Test
    void sameGoalNoMatchSuccessIsReusedWithoutRedisOrMq() {
        MediaFile file = media(
                9L, 7L, HASH, AnalysisStatus.SUCCESS,
                "## 目标分析结果\n\n- 视频内容不足以支持该分析目标");
        file.setAnalysisMode(AnalysisMode.GOAL);
        file.setAnalysisGoal("find launch risks");
        file.setAnalysisRequestId(UUID.randomUUID().toString());
        file.setResultMode(AnalysisMode.GOAL);
        file.setResultGoal("find launch risks");
        when(mediaFileMapper.selectById(9L)).thenReturn(file);

        Map<String, Object> result = service.submitAnalysis(
                9L, 7L, "goal", "  find launch risks  ", false);

        assertEquals("REUSED", result.get("status"));
        assertEquals(AnalysisMode.GOAL, result.get("analysisMode"));
        assertEquals("find launch risks", result.get("analysisGoal"));
        verifyNoInteractions(activeKeyService, rocketMQTemplate, redissonClient);
    }

    @Test
    void historicalFullGoalIsIgnoredForCanonicalFullReuse() {
        MediaFile file = media(16L, 7L, HASH, AnalysisStatus.SUCCESS, "## historical result");
        file.setAnalysisMode(AnalysisMode.FULL);
        file.setAnalysisGoal("legacy goal retained by migration");
        file.setAnalysisRequestId("77777777-7777-4777-8777-777777777777");
        file.setResultMode(AnalysisMode.FULL);
        file.setResultGoal("legacy goal retained by migration");
        when(mediaFileMapper.selectById(16L)).thenReturn(file);

        Map<String, Object> result = service.submitAnalysis(16L, 7L, null, null, false);

        assertEquals("REUSED", result.get("status"));
        assertEquals(AnalysisMode.FULL, result.get("analysisMode"));
        assertEquals(FULL_GOAL, result.get("analysisGoal"));
        verifyNoInteractions(activeKeyService, rocketMQTemplate, redissonClient);
    }

    @Test
    void changingModeCreatesNewRequestWithoutForce() {
        MediaFile file = media(14L, 7L, HASH, AnalysisStatus.SUCCESS, "## full result");
        when(mediaFileMapper.selectById(14L)).thenReturn(file);
        stubAcceptedSubmission(file);

        Map<String, Object> result = service.submitAnalysis(
                14L, 7L, "GOAL", "find security issues", false);

        assertEquals("SUBMITTED", result.get("status"));
        String requestId = (String) result.get("analysisRequestId");
        assertNotNull(requestId);
        assertNotEquals(file.getAnalysisRequestId(), requestId);
        verify(mediaFileMapper).queueAnalysis(
                14L, requestId, AnalysisMode.GOAL, "find security issues",
                AnalysisStatus.SUCCESS, null);
    }

    @Test
    void differentGoalDoesNotReuseNoMatchSuccess() {
        MediaFile file = media(
                15L, 7L, HASH, AnalysisStatus.SUCCESS,
                "## 目标分析结果\n\n- 视频内容不足以支持该分析目标");
        file.setAnalysisMode(AnalysisMode.GOAL);
        file.setAnalysisGoal("old goal");
        file.setAnalysisRequestId("66666666-6666-4666-8666-666666666666");
        file.setResultMode(AnalysisMode.GOAL);
        file.setResultGoal("old goal");
        when(mediaFileMapper.selectById(15L)).thenReturn(file);
        stubAcceptedSubmission(file);

        Map<String, Object> result = service.submitAnalysis(
                15L, 7L, "GOAL", "new goal", false);

        assertEquals("SUBMITTED", result.get("status"));
        String requestId = (String) result.get("analysisRequestId");
        assertNotEquals(file.getAnalysisRequestId(), requestId);
        verify(mediaFileMapper).queueAnalysis(
                15L, requestId, AnalysisMode.GOAL, "new goal",
                AnalysisStatus.SUCCESS, file.getAnalysisRequestId());
    }

    @Test
    void changedModeWithOccupiedActiveKeyReturnsRunningWithoutFakeRequestId() {
        MediaFile file = media(17L, 7L, HASH, AnalysisStatus.SUCCESS, "## old result");
        file.setAnalysisRequestId("88888888-8888-4888-8888-888888888888");
        when(mediaFileMapper.selectById(17L)).thenReturn(file);
        stubRedisOperations();
        when(redissonClient.getRateLimiter("limit:ai:global")).thenReturn(rateLimiter);
        when(rateLimiter.tryAcquire(1)).thenReturn(true);
        when(valueOperations.get(AnalysisRedisKeys.contentHash(17L))).thenReturn(HASH);
        when(activeKeyService.tryAcquire(
                eq(AnalysisRedisKeys.active(7L, HASH)),
                anyString(),
                eq(Duration.ofHours(2)))).thenReturn(false);

        Map<String, Object> result = service.submitAnalysis(
                17L, 7L, "GOAL", "new target", false);

        assertEquals("RUNNING", result.get("status"));
        assertEquals(AnalysisMode.FULL, result.get("analysisMode"));
        assertEquals(FULL_GOAL, result.get("analysisGoal"));
        org.junit.jupiter.api.Assertions.assertFalse(result.containsKey("analysisRequestId"));
        verify(mediaFileMapper, never()).queueAnalysis(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(rocketMQTemplate);
    }

    @ParameterizedTest
    @EnumSource(value = AnalysisStatus.class, names = {"QUEUED", "RUNNING"})
    void inProgressAnalysisDoesNotCreateAnotherRequestOrSendMq(AnalysisStatus status) {
        MediaFile file = media(3L, 7L, HASH, status, "## old result");
        file.setAnalysisRequestId(UUID.randomUUID().toString());
        when(mediaFileMapper.selectById(3L)).thenReturn(file);

        Map<String, Object> result = service.submitAnalysis(3L, 7L, null, false);

        assertEquals("RUNNING", result.get("status"));
        verifyNoInteractions(activeKeyService, rocketMQTemplate, redissonClient);
        verify(mediaFileMapper, never()).queueAnalysis(any(), any(), any(), any(), any(), any());
    }

    @Test
    void mqFailureDoesNotOverwriteRequestThatAlreadyAdvancedToRunning() {
        MediaFile file = media(4L, 7L, HASH, AnalysisStatus.SUCCESS, "## old result");
        when(mediaFileMapper.selectById(4L)).thenReturn(file);
        stubAcceptedSubmission(file);
        AtomicReference<AnalysisStatus> databaseStatus = new AtomicReference<>(AnalysisStatus.RUNNING);
        doThrow(new RuntimeException("broker unavailable"))
                .when(rocketMQTemplate)
                .convertAndSend(eq("video-analysis-topic"), any(AnalysisTaskMsg.class));
        when(mediaFileMapper.markSubmitFailed(
                eq(4L), anyString(), eq("broker unavailable"), any(LocalDateTime.class)))
                .thenAnswer(invocation -> databaseStatus.compareAndSet(
                        AnalysisStatus.QUEUED, AnalysisStatus.FAILED) ? 1 : 0);

        assertThrows(IllegalStateException.class,
                () -> service.submitAnalysis(4L, 7L, null, true));

        ArgumentCaptor<String> requestId = ArgumentCaptor.forClass(String.class);
        verify(mediaFileMapper).queueAnalysis(
                eq(4L), requestId.capture(), eq(AnalysisMode.FULL), eq(FULL_GOAL),
                eq(AnalysisStatus.SUCCESS), eq(null));
        verify(activeKeyService).deleteIfOwned(
                AnalysisRedisKeys.active(7L, HASH), requestId.getValue());
        verify(mediaFileMapper).markSubmitFailed(
                eq(4L), eq(requestId.getValue()), eq("broker unavailable"), any(LocalDateTime.class));
        verify(mediaFileMapper, never()).markExecutionFailed(any(), any(), any(), any());
        assertEquals(AnalysisStatus.RUNNING, databaseStatus.get());
        assertEquals("## old result", file.getAiSummary());
    }

    @Test
    void queueCasMissDoesNotSendMqAndDeletesOwnedActiveKey() {
        MediaFile original = media(20L, 7L, HASH, AnalysisStatus.FAILED, null);
        MediaFile latest = media(20L, 7L, HASH, AnalysisStatus.NOT_STARTED, null);
        latest.setAnalysisRequestId("33333333-3333-4333-8333-333333333333");
        when(mediaFileMapper.selectById(20L)).thenReturn(original, latest);
        stubSubmissionPrerequisites(original);
        when(mediaFileMapper.queueAnalysis(
                eq(20L), anyString(), eq(AnalysisMode.FULL), eq(FULL_GOAL),
                eq(AnalysisStatus.FAILED), eq(null)))
                .thenReturn(0);

        assertThrows(MediaAnalysisTaskService.AnalysisStateConflictException.class,
                () -> service.submitAnalysis(20L, 7L, null, false));

        ArgumentCaptor<String> requestId = ArgumentCaptor.forClass(String.class);
        verify(mediaFileMapper).queueAnalysis(
                eq(20L), requestId.capture(), eq(AnalysisMode.FULL), eq(FULL_GOAL),
                eq(AnalysisStatus.FAILED), eq(null));
        verify(activeKeyService).deleteIfOwned(
                AnalysisRedisKeys.active(7L, HASH), requestId.getValue());
        verifyNoInteractions(rocketMQTemplate);
    }

    @Test
    void queueCasMissReloadsConcurrentSuccessAsReused() {
        MediaFile original = media(21L, 7L, HASH, AnalysisStatus.FAILED, null);
        MediaFile latest = media(21L, 7L, HASH, AnalysisStatus.SUCCESS, "## concurrent result");
        latest.setAnalysisRequestId("44444444-4444-4444-8444-444444444444");
        when(mediaFileMapper.selectById(21L)).thenReturn(original, latest);
        stubSubmissionPrerequisites(original);
        when(mediaFileMapper.queueAnalysis(
                eq(21L), anyString(), eq(AnalysisMode.FULL), eq(FULL_GOAL),
                eq(AnalysisStatus.FAILED), eq(null)))
                .thenReturn(0);

        Map<String, Object> result = service.submitAnalysis(21L, 7L, null, false);

        assertEquals("REUSED", result.get("status"));
        assertEquals(latest.getAnalysisRequestId(), result.get("analysisRequestId"));
        verify(activeKeyService).deleteIfOwned(
                eq(AnalysisRedisKeys.active(7L, HASH)), anyString());
        verifyNoInteractions(rocketMQTemplate);
    }

    @ParameterizedTest
    @EnumSource(value = AnalysisStatus.class, names = {"QUEUED", "RUNNING"})
    void queueCasMissReloadsConcurrentInProgressStateAsRunning(AnalysisStatus latestStatus) {
        MediaFile original = media(22L, 7L, HASH, AnalysisStatus.FAILED, null);
        MediaFile latest = media(22L, 7L, HASH, latestStatus, "## old result");
        latest.setAnalysisRequestId("55555555-5555-4555-8555-555555555555");
        when(mediaFileMapper.selectById(22L)).thenReturn(original, latest);
        stubSubmissionPrerequisites(original);
        when(mediaFileMapper.queueAnalysis(
                eq(22L), anyString(), eq(AnalysisMode.FULL), eq(FULL_GOAL),
                eq(AnalysisStatus.FAILED), eq(null)))
                .thenReturn(0);

        Map<String, Object> result = service.submitAnalysis(22L, 7L, null, false);

        assertEquals("RUNNING", result.get("status"));
        assertEquals(latest.getAnalysisRequestId(), result.get("analysisRequestId"));
        verify(activeKeyService).deleteIfOwned(
                eq(AnalysisRedisKeys.active(7L, HASH)), anyString());
        verifyNoInteractions(rocketMQTemplate);
    }

    @Test
    void unattributedHistoricalSummaryIsNotReused() {
        MediaFile file = media(5L, 7L, HASH, AnalysisStatus.NOT_STARTED, "## historical result");
        file.setResultRequestId(null);
        file.setResultMode(null);
        file.setResultGoal(null);
        when(mediaFileMapper.selectById(5L)).thenReturn(file);
        stubAcceptedSubmission(file);

        Map<String, Object> result = service.submitAnalysis(5L, 7L, null, false);

        assertEquals("SUBMITTED", result.get("status"));
        verify(rocketMQTemplate).convertAndSend(eq("video-analysis-topic"), any(AnalysisTaskMsg.class));
    }

    @Test
    void failedBStillReusesSuccessfulAByResultMetadata() {
        MediaFile file = media(23L, 7L, HASH, AnalysisStatus.FAILED, "## result A");
        file.setAnalysisRequestId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        file.setAnalysisMode(AnalysisMode.GOAL);
        file.setAnalysisGoal("question B");
        file.setAnalysisError("provider unavailable");
        file.setResultRequestId(RESULT_REQUEST_ID);
        file.setResultMode(AnalysisMode.GOAL);
        file.setResultGoal("question A");
        when(mediaFileMapper.selectById(23L)).thenReturn(file);

        Map<String, Object> result = service.submitAnalysis(
                23L, 7L, "GOAL", "question A", false);

        assertEquals("REUSED", result.get("status"));
        assertEquals("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", result.get("analysisRequestId"));
        assertEquals(AnalysisMode.GOAL, result.get("analysisMode"));
        assertEquals("question B", result.get("analysisGoal"));
        assertEquals(AnalysisStatus.FAILED, result.get("analysisStatus"));
        assertEquals(RESULT_REQUEST_ID, result.get("resultRequestId"));
        assertEquals(AnalysisMode.GOAL, result.get("resultMode"));
        assertEquals("question A", result.get("resultGoal"));
        verifyNoInteractions(activeKeyService, rocketMQTemplate, redissonClient);
    }

    @Test
    void failedBCanBeRetriedWithoutReusingSuccessfulA() {
        MediaFile file = media(24L, 7L, HASH, AnalysisStatus.FAILED, "## result A");
        file.setAnalysisRequestId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        file.setAnalysisMode(AnalysisMode.GOAL);
        file.setAnalysisGoal("question B");
        file.setResultRequestId(RESULT_REQUEST_ID);
        file.setResultMode(AnalysisMode.GOAL);
        file.setResultGoal("question A");
        when(mediaFileMapper.selectById(24L)).thenReturn(file);
        stubAcceptedSubmission(file);

        Map<String, Object> result = service.submitAnalysis(
                24L, 7L, "GOAL", "question B", false);

        assertEquals("SUBMITTED", result.get("status"));
        String retryRequestId = (String) result.get("analysisRequestId");
        verify(mediaFileMapper).queueAnalysis(
                24L, retryRequestId, AnalysisMode.GOAL, "question B",
                AnalysisStatus.FAILED, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        verify(rocketMQTemplate).convertAndSend(eq("video-analysis-topic"), any(AnalysisTaskMsg.class));
    }

    @Test
    void failedAnalysisCanBeSubmittedAgain() {
        MediaFile file = media(6L, 7L, HASH, AnalysisStatus.FAILED, null);
        when(mediaFileMapper.selectById(6L)).thenReturn(file);
        stubAcceptedSubmission(file);

        Map<String, Object> result = service.submitAnalysis(6L, 7L, null, false);

        assertEquals("SUBMITTED", result.get("status"));
        verify(rocketMQTemplate).convertAndSend(eq("video-analysis-topic"), any(AnalysisTaskMsg.class));
    }

    @Test
    void differentUsersAndContentHashesUseIndependentActiveKeys() {
        assertNotEquals(
                AnalysisRedisKeys.active(7L, HASH),
                AnalysisRedisKeys.active(8L, HASH));
        assertNotEquals(
                AnalysisRedisKeys.active(7L, HASH),
                AnalysisRedisKeys.active(7L, OTHER_HASH));
    }

    @Test
    void redisHitUsesCachedMd5WithoutRecalculation() throws Exception {
        stubRedisOperations();
        MediaFile file = media(10L, 7L, HASH, AnalysisStatus.NOT_STARTED, null);
        when(valueOperations.get(AnalysisRedisKeys.contentHash(10L))).thenReturn(HASH);

        assertEquals(HASH, service.resolveContentHash(file));

        verify(mediaService, never()).calculateStoredContentHash(any());
        verify(mediaService, never()).rememberContentHash(any(), any());
    }

    @Test
    void redisMissUsesMysqlAndRepopulatesCache() throws Exception {
        stubRedisOperations();
        MediaFile file = media(11L, 7L, HASH, AnalysisStatus.NOT_STARTED, null);
        when(valueOperations.get(AnalysisRedisKeys.contentHash(11L))).thenReturn(null);

        assertEquals(HASH, service.resolveContentHash(file));

        verify(mediaService).rememberContentHash(11L, HASH);
        verify(mediaService, never()).calculateStoredContentHash(any());
    }

    @Test
    void historicalRowIsCalculatedThenPersistedBeforeCaching() throws Exception {
        stubRedisOperations();
        MediaFile file = media(12L, 7L, null, AnalysisStatus.NOT_STARTED, null);
        when(valueOperations.get(AnalysisRedisKeys.contentHash(12L))).thenReturn(null);
        when(redissonClient.getLock(AnalysisRedisKeys.contentHashLock(12L))).thenReturn(contentHashLock);
        when(contentHashLock.isHeldByCurrentThread()).thenReturn(true);
        when(mediaFileMapper.selectById(12L)).thenReturn(file);
        when(mediaService.calculateStoredContentHash(file)).thenReturn(HASH);
        when(mediaFileMapper.updateById(any(MediaFile.class))).thenReturn(1);

        assertEquals(HASH, service.resolveContentHash(file));

        ArgumentCaptor<MediaFile> patch = ArgumentCaptor.forClass(MediaFile.class);
        InOrder persistenceOrder = inOrder(mediaFileMapper, mediaService);
        persistenceOrder.verify(mediaFileMapper).updateById(patch.capture());
        persistenceOrder.verify(mediaService).rememberContentHash(12L, HASH);
        assertEquals(HASH, patch.getValue().getContentHash());
        verify(contentHashLock).unlock();
    }

    @Test
    void inaccessibleHistoricalRowUsesExplicitNonPersistentFallback() throws Exception {
        stubRedisOperations();
        MediaFile file = media(13L, 7L, null, AnalysisStatus.NOT_STARTED, null);
        when(valueOperations.get(AnalysisRedisKeys.contentHash(13L))).thenReturn(null);
        when(redissonClient.getLock(AnalysisRedisKeys.contentHashLock(13L))).thenReturn(contentHashLock);
        when(contentHashLock.isHeldByCurrentThread()).thenReturn(true);
        when(mediaFileMapper.selectById(13L)).thenReturn(file);
        when(mediaService.calculateStoredContentHash(file)).thenThrow(new IOException("not accessible"));

        assertEquals("legacy-media:13", service.resolveContentHash(file));

        verify(mediaFileMapper, never()).updateById(any(MediaFile.class));
        verify(mediaService, never()).rememberContentHash(any(), any());
    }

    @Test
    void analysisKeysRejectMissingUserId() {
        assertThrows(IllegalArgumentException.class,
                () -> AnalysisRedisKeys.active(null, HASH));
        assertThrows(IllegalArgumentException.class,
                () -> AnalysisRedisKeys.analysisLock(null, HASH));
    }

    private void stubAcceptedSubmission(MediaFile file) {
        stubSubmissionPrerequisites(file);
        when(mediaFileMapper.queueAnalysis(
                eq(file.getId()),
                anyString(),
                any(AnalysisMode.class),
                anyString(),
                eq(file.getAnalysisStatus()),
                eq(file.getAnalysisRequestId()))).thenReturn(1);
    }

    private void stubSubmissionPrerequisites(MediaFile file) {
        stubRedisOperations();
        when(redissonClient.getRateLimiter("limit:ai:global")).thenReturn(rateLimiter);
        when(rateLimiter.tryAcquire(1)).thenReturn(true);
        when(valueOperations.get(AnalysisRedisKeys.contentHash(file.getId()))).thenReturn(file.getContentHash());
        when(activeKeyService.tryAcquire(
                eq(AnalysisRedisKeys.active(file.getUserId(), file.getContentHash())),
                anyString(),
                eq(Duration.ofHours(2)))).thenReturn(true);
    }

    private void stubRedisOperations() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private MediaFile media(Long id, Long userId, String contentHash,
                            AnalysisStatus status, String aiSummary) {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setUserId(userId);
        file.setContentHash(contentHash);
        file.setAnalysisStatus(status);
        file.setAnalysisMode(AnalysisMode.FULL);
        file.setAnalysisGoal(FULL_GOAL);
        file.setAiSummary(aiSummary);
        if (aiSummary != null) {
            file.setResultRequestId(RESULT_REQUEST_ID);
            file.setResultMode(AnalysisMode.FULL);
            file.setResultGoal(FULL_GOAL);
            file.setResultFinishedAt(LocalDateTime.of(2026, 8, 1, 12, 0));
        }
        file.setFilePath("unused-in-unit-test");
        return file;
    }
}
