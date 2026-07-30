package com.example.server.service;

import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.entity.AnalysisMode;
import com.example.server.utils.DeepSeekUtils;
import com.example.server.utils.EmbeddingUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class LongVideoContextService {

    private static final long CHUNK_MS = 5 * 60 * 1000L;
    private static final int TOP_K = 3;

    @Autowired
    private DeepSeekUtils deepSeekUtils;

    @Autowired
    private EmbeddingUtils embeddingUtils;

    public VideoContext selectRelevant(VideoContext context) {
        if (context.segments().isEmpty()
                || context.segments().get(context.segments().size() - 1).endMs() <= CHUNK_MS) {
            return context;
        }

        List<VideoChunk> chunks = summarizeChunks(context.segments());
        if (context.analysisMode() == AnalysisMode.FULL) {
            List<VideoContext.VideoSegment> summarySegments = chunks.stream()
                    .sorted(Comparator.comparingLong(VideoChunk::startTime))
                    .map(this::toSummarySegment)
                    .toList();
            return new VideoContext(
                    context.source(), context.analysisMode(), context.userGoal(), summarySegments);
        }

        List<VideoChunk> embeddedChunks = chunks.stream()
                .map(this::withEmbedding)
                .toList();
        List<Double> queryEmbedding = embeddingUtils.embed(context.userGoal());

        List<VideoContext.VideoSegment> selectedSegments = embeddedChunks.stream()
                .sorted(Comparator.comparingDouble(
                        (VideoChunk chunk) -> cosine(queryEmbedding, chunk.embedding())
                ).reversed())
                .limit(TOP_K)
                .flatMap(chunk -> chunk.rawSegments().stream())
                .sorted(Comparator.comparingLong(VideoContext.VideoSegment::startMs))
                .toList();

        return new VideoContext(
                context.source(), context.analysisMode(), context.userGoal(), selectedSegments);
    }

    private List<VideoChunk> summarizeChunks(List<VideoContext.VideoSegment> segments) {
        List<VideoChunk> chunks = new ArrayList<>();
        for (long start = 0; start <= segments.get(segments.size() - 1).startMs(); start += CHUNK_MS) {
            long end = start + CHUNK_MS;
            long chunkStart = start;
            List<VideoContext.VideoSegment> rawSegments = segments.stream()
                    .filter(segment -> segment.startMs() >= chunkStart && segment.startMs() < end)
                    .toList();
            if (rawSegments.isEmpty()) continue;

            VideoChunk.ChunkSummary summary = deepSeekUtils.summarizeChunk(rawSegments);
            chunks.add(new VideoChunk(
                    start,
                    rawSegments.get(rawSegments.size() - 1).endMs(),
                    summary.segmentSummary(),
                    summary.keywords(),
                    rawSegments,
                    List.of()
            ));
        }
        return chunks;
    }

    private VideoChunk withEmbedding(VideoChunk chunk) {
        String embeddingText = chunk.segmentSummary() + "\n" + String.join(" ", chunk.keywords());
        return new VideoChunk(
                chunk.startTime(),
                chunk.endTime(),
                chunk.segmentSummary(),
                chunk.keywords(),
                chunk.rawSegments(),
                embeddingUtils.embed(embeddingText)
        );
    }

    private VideoContext.VideoSegment toSummarySegment(VideoChunk chunk) {
        String keywords = chunk.keywords().isEmpty()
                ? ""
                : "\n关键词：" + String.join("、", chunk.keywords());
        return new VideoContext.VideoSegment(
                chunk.startTime(),
                chunk.endTime(),
                "片段摘要：" + chunk.segmentSummary() + keywords,
                "CHUNK_SUMMARY",
                List.of(),
                List.of()
        );
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.size() != right.size() || left.isEmpty()) return 0;

        double dot = 0;
        double leftLength = 0;
        double rightLength = 0;
        for (int i = 0; i < left.size(); i++) {
            dot += left.get(i) * right.get(i);
            leftLength += left.get(i) * left.get(i);
            rightLength += right.get(i) * right.get(i);
        }
        if (leftLength == 0 || rightLength == 0) return 0;
        return dot / (Math.sqrt(leftLength) * Math.sqrt(rightLength));
    }
}
