package com.example.server.service;

import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.entity.AnalysisMode;
import com.example.server.utils.DeepSeekUtils;
import com.example.server.utils.EmbeddingUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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

    private LongVideoContextService service;

    @BeforeEach
    void setUp() {
        service = new LongVideoContextService();
        ReflectionTestUtils.setField(service, "deepSeekUtils", deepSeekUtils);
        ReflectionTestUtils.setField(service, "embeddingUtils", embeddingUtils);
    }

    @ParameterizedTest
    @EnumSource(AnalysisMode.class)
    void shortVideoKeepsCompleteRawContextForBothModes(AnalysisMode mode) {
        List<VideoContext.VideoSegment> segments = rawSegments(5);
        VideoContext context = new VideoContext("video.mp4", mode, goal(mode), segments);

        VideoContext selected = service.selectRelevant(context);

        assertSame(context, selected);
        assertSame(segments, selected.segments());
        verifyNoInteractions(deepSeekUtils, embeddingUtils);
    }

    @Test
    void fullLongVideoUsesEveryChunkSummaryWithoutEmbeddingOrTop3() {
        stubChunkSummaries();
        VideoContext context = new VideoContext(
                "video.mp4", AnalysisMode.FULL, "full internal goal", rawSegments(20));

        VideoContext selected = service.selectRelevant(context);

        assertEquals(AnalysisMode.FULL, selected.analysisMode());
        assertEquals(4, selected.segments().size());
        assertEquals(List.of(0L, 5 * MINUTE_MS, 10 * MINUTE_MS, 15 * MINUTE_MS),
                selected.segments().stream().map(VideoContext.VideoSegment::startMs).toList());
        for (int i = 0; i < selected.segments().size(); i++) {
            VideoContext.VideoSegment segment = selected.segments().get(i);
            String transcript = segment.transcript();
            assertTrue(transcript.contains("summary-" + i));
            assertTrue(transcript.contains("keyword-" + i));
            assertEquals("CHUNK_SUMMARY", segment.contentSource());
            assertEquals((i + 1) * 5 * MINUTE_MS, segment.endMs());
        }
        verify(deepSeekUtils, org.mockito.Mockito.times(4)).summarizeChunk(anyList());
        verify(embeddingUtils, never()).embed(anyString());
    }

    @Test
    void goalLongVideoRetrievesTop3ChunksAsOriginalSegments() {
        stubChunkSummaries();
        when(embeddingUtils.embed(anyString())).thenAnswer(invocation -> {
            String input = invocation.getArgument(0, String.class);
            return switch (input) {
                case "summary-0\nkeyword-0" -> List.of(0.0, 1.0);
                case "summary-1\nkeyword-1" -> List.of(1.0, 0.0);
                case "summary-2\nkeyword-2" -> List.of(0.9, 0.1);
                case "summary-3\nkeyword-3" -> List.of(0.8, 0.2);
                case "target" -> List.of(1.0, 0.0);
                default -> throw new AssertionError("Unexpected embedding input");
            };
        });
        VideoContext context = new VideoContext(
                "video.mp4", AnalysisMode.GOAL, "target", rawSegments(20));

        VideoContext selected = service.selectRelevant(context);

        assertEquals(AnalysisMode.GOAL, selected.analysisMode());
        assertEquals(15, selected.segments().size());
        assertEquals(5 * MINUTE_MS, selected.segments().getFirst().startMs());
        assertEquals(20 * MINUTE_MS, selected.segments().getLast().endMs());
        assertTrue(selected.segments().stream()
                .allMatch(segment -> segment.transcript().startsWith("raw-")));
        verify(embeddingUtils).embed("target");
        verify(embeddingUtils, org.mockito.Mockito.times(5)).embed(anyString());
    }

    private void stubChunkSummaries() {
        when(deepSeekUtils.summarizeChunk(anyList())).thenAnswer(invocation -> {
            List<VideoContext.VideoSegment> segments = invocation.getArgument(0);
            int chunkIndex = Math.toIntExact(segments.getFirst().startMs() / (5 * MINUTE_MS));
            return new VideoChunk.ChunkSummary(
                    "summary-" + chunkIndex, List.of("keyword-" + chunkIndex));
        });
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

    private String goal(AnalysisMode mode) {
        return mode == AnalysisMode.FULL ? "full internal goal" : "target";
    }
}
