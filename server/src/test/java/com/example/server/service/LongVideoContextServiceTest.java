package com.example.server.service;

import com.example.server.config.VideoGoalRelevanceProperties;
import com.example.server.dto.ContextSelectionResult;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.entity.AnalysisMode;
import com.example.server.utils.DeepSeekUtils;
import com.example.server.utils.EmbeddingUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LongVideoContextServiceTest {

    private static final long MINUTE_MS = 60_000L;

    @Mock
    private DeepSeekUtils deepSeekUtils;
    @Mock
    private EmbeddingUtils embeddingUtils;

    private VideoGoalRelevanceProperties properties;
    private LongVideoContextService service;

    @BeforeEach
    void setUp() {
        properties = new VideoGoalRelevanceProperties();
        service = new LongVideoContextService(deepSeekUtils, embeddingUtils, properties);
    }

    @Test
    void fullShortVideoKeepsCompleteRawContextWithoutEmbedding() {
        List<VideoContext.VideoSegment> segments = rawSegments(5);
        VideoContext context = context(AnalysisMode.FULL, segments);

        ContextSelectionResult selection = service.selectRelevant(context);

        assertEquals(ContextSelectionResult.Status.MATCHED, selection.status());
        assertSame(context, selection.context());
        assertSame(segments, selection.context().segments());
        verifyNoInteractions(deepSeekUtils, embeddingUtils);
    }

    @Test
    void fullLongVideoUsesEveryChunkSummaryWithoutEmbeddingOrConfiguredTopK() {
        properties.setTopK(1);
        properties.setLongThreshold(1.0);
        stubChunkSummaries();
        VideoContext context = context(AnalysisMode.FULL, rawSegments(20));

        ContextSelectionResult selection = service.selectRelevant(context);

        assertEquals(ContextSelectionResult.Status.MATCHED, selection.status());
        VideoContext selected = selection.context();
        assertEquals(4, selected.segments().size());
        assertEquals(List.of(0L, 5 * MINUTE_MS, 10 * MINUTE_MS, 15 * MINUTE_MS),
                selected.segments().stream().map(VideoContext.VideoSegment::startMs).toList());
        for (int i = 0; i < selected.segments().size(); i++) {
            VideoContext.VideoSegment segment = selected.segments().get(i);
            assertTrue(segment.transcript().contains("summary-" + i));
            assertTrue(segment.transcript().contains("keyword-" + i));
            assertEquals("CHUNK_SUMMARY", segment.contentSource());
            assertEquals((i + 1) * 5 * MINUTE_MS, segment.endMs());
        }
        verify(deepSeekUtils, times(4)).summarizeChunk(anyList());
        verify(embeddingUtils, never()).embed(anyString());
    }

    @Test
    void goalShortVideoAboveThresholdMatchesAndKeepsCompleteRawContext() {
        VideoContext context = context(AnalysisMode.GOAL, rawSegments(5));
        stubShortEmbeddings(List.of(0.8, 0.6));

        ContextSelectionResult selection = service.selectRelevant(context);

        assertEquals(ContextSelectionResult.Status.MATCHED, selection.status());
        assertSame(context, selection.context());
        verify(embeddingUtils).embed("target");
        verify(embeddingUtils).embed(shortSearchText(5));
        verify(embeddingUtils, times(2)).embed(anyString());
        verifyNoInteractions(deepSeekUtils);
    }

    @Test
    void goalShortVideoBelowThresholdReturnsNoMatch() {
        VideoContext context = context(AnalysisMode.GOAL, rawSegments(5));
        stubShortEmbeddings(List.of(0.0, 1.0));

        ContextSelectionResult selection = service.selectRelevant(context);

        assertEquals(ContextSelectionResult.Status.NO_MATCH, selection.status());
        assertTrue(selection.context().segments().isEmpty());
    }

    @Test
    void goalShortVideoEqualToThresholdMatches() {
        properties.setShortThreshold(1.0);
        VideoContext context = context(AnalysisMode.GOAL, rawSegments(5));
        stubShortEmbeddings(List.of(1.0, 0.0));

        ContextSelectionResult selection = service.selectRelevant(context);

        assertEquals(ContextSelectionResult.Status.MATCHED, selection.status());
    }

    @Test
    void goalShortVideoWithNoAsrOrOcrTextReturnsNoMatchWithoutEmbedding() {
        VideoContext context = context(AnalysisMode.GOAL, List.of(
                new VideoContext.VideoSegment(
                        0, MINUTE_MS, "  ", "ASR", List.of("", "  "), List.of())
        ));

        ContextSelectionResult selection = service.selectRelevant(context);

        assertEquals(ContextSelectionResult.Status.NO_MATCH, selection.status());
        verifyNoInteractions(deepSeekUtils, embeddingUtils);
    }

    @ParameterizedTest(name = "{0} embedding scores zero and does not match")
    @MethodSource("invalidEmbeddings")
    void invalidShortVideoEmbeddingScoresZero(String description, List<Double> invalidEmbedding) {
        VideoContext context = context(AnalysisMode.GOAL, rawSegments(5));
        when(embeddingUtils.embed(anyString())).thenAnswer(invocation ->
                "target".equals(invocation.getArgument(0, String.class))
                        ? List.of(1.0, 0.0)
                        : invalidEmbedding);

        ContextSelectionResult selection = service.selectRelevant(context);

        assertEquals(ContextSelectionResult.Status.NO_MATCH, selection.status());
    }

    @Test
    void embeddingApiFailureIsNotConvertedToNoMatch() {
        VideoContext context = context(AnalysisMode.GOAL, rawSegments(5));
        IllegalStateException failure = new IllegalStateException("provider unavailable");
        when(embeddingUtils.embed("target")).thenThrow(failure);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> service.selectRelevant(context));

        assertSame(failure, thrown);
    }

    @Test
    void goalLongVideoFiltersThenLimitsToConfiguredTopK() {
        properties.setTopK(3);
        stubLongEmbeddings(List.of(
                List.of(0.6, 0.8),
                List.of(1.0, 0.0),
                List.of(0.8, 0.6),
                List.of(0.7, Math.sqrt(0.51)),
                List.of(0.5, Math.sqrt(0.75))
        ));
        VideoContext context = context(AnalysisMode.GOAL, rawSegments(25));

        ContextSelectionResult selection = service.selectRelevant(context);

        assertEquals(ContextSelectionResult.Status.MATCHED, selection.status());
        assertEquals(List.of(5L, 10L, 15L),
                selectedChunkIndexes(selection.context()));
    }

    @Test
    void goalLongVideoWithOnlyTwoMatchesDoesNotBackfill() {
        stubLongEmbeddings(List.of(
                List.of(0.8, 0.6),
                List.of(0.0, 1.0),
                List.of(0.6, 0.8),
                List.of(0.4, Math.sqrt(0.84))
        ));
        VideoContext context = context(AnalysisMode.GOAL, rawSegments(20));

        ContextSelectionResult selection = service.selectRelevant(context);

        assertEquals(ContextSelectionResult.Status.MATCHED, selection.status());
        assertEquals(List.of(0L, 10L), selectedChunkIndexes(selection.context()));
        assertEquals(10, selection.context().segments().size());
    }

    @Test
    void goalLongVideoWithOnlyOneMatchDoesNotBackfill() {
        stubLongEmbeddings(List.of(
                List.of(0.0, 1.0),
                List.of(0.8, 0.6),
                List.of(0.4, Math.sqrt(0.84)),
                List.of(0.3, Math.sqrt(0.91))
        ));
        VideoContext context = context(AnalysisMode.GOAL, rawSegments(20));

        ContextSelectionResult selection = service.selectRelevant(context);

        assertEquals(ContextSelectionResult.Status.MATCHED, selection.status());
        assertEquals(List.of(5L), selectedChunkIndexes(selection.context()));
        assertEquals(5, selection.context().segments().size());
    }

    @Test
    void goalLongVideoWithEveryChunkBelowThresholdReturnsNoMatch() {
        stubLongEmbeddings(List.of(
                List.of(0.4, Math.sqrt(0.84)),
                List.of(0.3, Math.sqrt(0.91)),
                List.of(0.2, Math.sqrt(0.96)),
                List.of(0.0, 1.0)
        ));
        VideoContext context = context(AnalysisMode.GOAL, rawSegments(20));

        ContextSelectionResult selection = service.selectRelevant(context);

        assertEquals(ContextSelectionResult.Status.NO_MATCH, selection.status());
        assertTrue(selection.context().segments().isEmpty());
    }

    @Test
    void equalLongVideoScoresUseStartTimeAsStableTieBreaker() {
        properties.setTopK(2);
        stubLongEmbeddings(List.of(
                List.of(1.0, 0.0),
                List.of(1.0, 0.0),
                List.of(1.0, 0.0),
                List.of(0.0, 1.0)
        ));
        VideoContext context = context(AnalysisMode.GOAL, rawSegments(20));

        ContextSelectionResult selection = service.selectRelevant(context);

        assertEquals(List.of(0L, 5L), selectedChunkIndexes(selection.context()));
    }

    private static Stream<Arguments> invalidEmbeddings() {
        return Stream.of(
                Arguments.of("empty", List.of()),
                Arguments.of("zero", List.of(0.0, 0.0)),
                Arguments.of("dimension-mismatched", List.of(1.0))
        );
    }

    private void stubShortEmbeddings(List<Double> contextEmbedding) {
        when(embeddingUtils.embed(anyString())).thenAnswer(invocation ->
                "target".equals(invocation.getArgument(0, String.class))
                        ? List.of(1.0, 0.0)
                        : contextEmbedding);
    }

    private void stubLongEmbeddings(List<List<Double>> chunkEmbeddings) {
        stubChunkSummaries();
        when(embeddingUtils.embed(anyString())).thenAnswer(invocation -> {
            String input = invocation.getArgument(0, String.class);
            if ("target".equals(input)) {
                return List.of(1.0, 0.0);
            }
            for (int i = 0; i < chunkEmbeddings.size(); i++) {
                if (input.equals("summary-" + i + "\nkeyword-" + i)) {
                    return chunkEmbeddings.get(i);
                }
            }
            throw new AssertionError("Unexpected embedding input: " + input);
        });
    }

    private void stubChunkSummaries() {
        when(deepSeekUtils.summarizeChunk(anyList())).thenAnswer(invocation -> {
            List<VideoContext.VideoSegment> segments = invocation.getArgument(0);
            int chunkIndex = Math.toIntExact(segments.getFirst().startMs() / (5 * MINUTE_MS));
            return new VideoChunk.ChunkSummary(
                    "summary-" + chunkIndex, List.of("keyword-" + chunkIndex));
        });
    }

    private List<Long> selectedChunkIndexes(VideoContext context) {
        return context.segments().stream()
                .map(segment -> segment.startMs() / MINUTE_MS)
                .filter(minute -> minute % 5 == 0)
                .toList();
    }

    private VideoContext context(AnalysisMode mode, List<VideoContext.VideoSegment> segments) {
        return new VideoContext("video.mp4", mode,
                mode == AnalysisMode.FULL ? "full internal goal" : "target",
                segments);
    }

    private String shortSearchText(int segmentCount) {
        List<String> text = new ArrayList<>();
        for (int i = 0; i < segmentCount; i++) {
            text.add("raw-" + i);
            text.add("ocr-" + i);
        }
        return String.join("\n", text);
    }

    private List<VideoContext.VideoSegment> rawSegments(int count) {
        List<VideoContext.VideoSegment> segments = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            segments.add(new VideoContext.VideoSegment(
                    i * MINUTE_MS,
                    (i + 1) * MINUTE_MS,
                    "raw-" + i,
                    List.of("ocr-" + i),
                    List.of("frame-" + i)
            ));
        }
        return segments;
    }
}
