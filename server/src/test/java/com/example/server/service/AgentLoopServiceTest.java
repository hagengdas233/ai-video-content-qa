package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import com.example.server.entity.AnalysisMode;
import com.example.server.utils.DeepSeekUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentLoopServiceTest {

    @Mock
    private DeepSeekUtils deepSeekUtils;
    @Mock
    private LongVideoContextService longVideoContextService;

    private AgentLoopService service;
    private VideoContext context;
    private VideoContext relevantContext;
    private AgentState.AgentPlan plan;

    @BeforeEach
    void setUp() {
        service = new AgentLoopService();
        ReflectionTestUtils.setField(service, "deepSeekUtils", deepSeekUtils);
        ReflectionTestUtils.setField(service, "longVideoContextService", longVideoContextService);

        context = new VideoContext("video.mp4", AnalysisMode.GOAL, "goal", List.of());
        relevantContext = new VideoContext("video.mp4", AnalysisMode.GOAL, "goal", List.of(
                new VideoContext.VideoSegment(0, 1_000, "transcript", List.of(), List.of())));
        plan = new AgentState.AgentPlan("goal", List.of("task"));
    }

    @Test
    void firstRoundExecutorFailureIsRethrown() {
        stubRelevantContext();
        RuntimeException failure = new IllegalStateException("executor failed");
        when(deepSeekUtils.execute(relevantContext, plan, null)).thenThrow(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.run(context));

        assertSame(failure, thrown);
        verify(deepSeekUtils, never()).critique(relevantContext, plan, null);
    }

    @Test
    void secondRoundExecutorFailureReturnsFirstRoundState() {
        stubRelevantContext();
        AnalysisResult firstResult = result("first");
        AgentState.CriticResult firstCritique = critique(false, "revise");
        when(deepSeekUtils.execute(relevantContext, plan, null)).thenReturn(firstResult);
        when(deepSeekUtils.critique(relevantContext, plan, firstResult)).thenReturn(firstCritique);
        when(deepSeekUtils.execute(relevantContext, plan, firstCritique))
                .thenThrow(new IllegalStateException("executor correction failed"));

        AgentState state = service.run(context);

        assertSame(firstResult, state.result());
        assertSame(firstCritique, state.critique());
        assertEquals(1, state.round());
        verify(deepSeekUtils).execute(relevantContext, plan, firstCritique);
    }

    @Test
    void firstRoundCriticFailureReturnsCurrentExecutorResultWithoutCritique() {
        stubRelevantContext();
        AnalysisResult firstResult = result("first");
        when(deepSeekUtils.execute(relevantContext, plan, null)).thenReturn(firstResult);
        when(deepSeekUtils.critique(relevantContext, plan, firstResult))
                .thenThrow(new IllegalStateException("critic failed"));

        AgentState state = service.run(context);

        assertSame(firstResult, state.result());
        assertNull(state.critique());
        assertEquals(1, state.round());
    }

    @Test
    void secondRoundExecutorAndPassingCriticReturnSecondRoundResultInOriginalOrder() {
        stubRelevantContext();
        AnalysisResult firstResult = result("first");
        AnalysisResult secondResult = result("second");
        AgentState.CriticResult firstCritique = critique(false, "revise");
        AgentState.CriticResult secondCritique = critique(true, "passed");
        when(deepSeekUtils.execute(relevantContext, plan, null)).thenReturn(firstResult);
        when(deepSeekUtils.critique(relevantContext, plan, firstResult)).thenReturn(firstCritique);
        when(deepSeekUtils.execute(relevantContext, plan, firstCritique)).thenReturn(secondResult);
        when(deepSeekUtils.critique(relevantContext, plan, secondResult)).thenReturn(secondCritique);

        AgentState state = service.run(context);

        assertSame(secondResult, state.result());
        assertSame(secondCritique, state.critique());
        assertEquals(2, state.round());
        InOrder order = inOrder(longVideoContextService, deepSeekUtils);
        order.verify(longVideoContextService).selectRelevant(context);
        order.verify(deepSeekUtils).plan(relevantContext);
        order.verify(deepSeekUtils).execute(relevantContext, plan, null);
        order.verify(deepSeekUtils).critique(relevantContext, plan, firstResult);
        order.verify(deepSeekUtils).execute(relevantContext, plan, firstCritique);
        order.verify(deepSeekUtils).critique(relevantContext, plan, secondResult);
    }

    @Test
    void evidenceMustMatchRawSourceAndTimestampInterval() {
        AnalysisResult result = new AnalysisResult(
                "title",
                List.of("conclusion"),
                List.of(
                        new AnalysisResult.Evidence(0, "CHUNK_SUMMARY", "fake summary"),
                        new AnalysisResult.Evidence(999, "ASR", "valid transcript"),
                        new AnalysisResult.Evidence(1_000, "ASR", "outside interval"),
                        new AnalysisResult.Evidence(0, "OCR", "missing OCR")
                ),
                List.of("suggestion")
        );

        AnalysisResult validated = service.validateEvidence(relevantContext, result);

        assertEquals(List.of(
                new AnalysisResult.Evidence(999, "ASR", "valid transcript")
        ), validated.evidence());
    }

    @Test
    void chunkSummaryEvidenceUsesItsExplicitSourceAndChunkStartOnly() {
        VideoContext summaryContext = new VideoContext(
                "video.mp4",
                AnalysisMode.FULL,
                "goal",
                List.of(new VideoContext.VideoSegment(
                        300_000,
                        600_000,
                        "summary",
                        "CHUNK_SUMMARY",
                        List.of(),
                        List.of()
                ))
        );
        AnalysisResult result = new AnalysisResult(
                "title",
                List.of("conclusion"),
                List.of(
                        new AnalysisResult.Evidence(300_000, "CHUNK_SUMMARY", "valid summary"),
                        new AnalysisResult.Evidence(300_001, "CHUNK_SUMMARY", "fake precision"),
                        new AnalysisResult.Evidence(300_000, "ASR", "fake ASR")
                ),
                List.of("suggestion")
        );

        AnalysisResult validated = service.validateEvidence(summaryContext, result);

        assertEquals(List.of(
                new AnalysisResult.Evidence(300_000, "CHUNK_SUMMARY", "valid summary")
        ), validated.evidence());
    }

    private AnalysisResult result(String title) {
        return new AnalysisResult(title, List.of("conclusion"), List.of(), List.of("suggestion"));
    }

    private void stubRelevantContext() {
        when(longVideoContextService.selectRelevant(context)).thenReturn(relevantContext);
        when(deepSeekUtils.plan(relevantContext)).thenReturn(plan);
    }

    private AgentState.CriticResult critique(boolean passed, String feedback) {
        return new AgentState.CriticResult(
                passed, List.of(feedback), List.of(), List.of(), List.of());
    }
}
