package com.example.server.service;

import com.example.server.config.VideoGoalRelevanceProperties;
import com.example.server.dto.ContextSelectionResult;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.entity.AnalysisMode;
import com.example.server.utils.DeepSeekUtils;
import com.example.server.utils.EmbeddingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

@Service
public class LongVideoContextService {

    private static final Logger log = LoggerFactory.getLogger(LongVideoContextService.class);
    private static final long CHUNK_MS = 5 * 60 * 1000L;

    private final DeepSeekUtils deepSeekUtils;
    private final EmbeddingUtils embeddingUtils;
    private final VideoGoalRelevanceProperties relevanceProperties;

    public LongVideoContextService(DeepSeekUtils deepSeekUtils,
                                   EmbeddingUtils embeddingUtils,
                                   VideoGoalRelevanceProperties relevanceProperties) {
        this.deepSeekUtils = deepSeekUtils;
        this.embeddingUtils = embeddingUtils;
        this.relevanceProperties = relevanceProperties;
    }

    public ContextSelectionResult selectRelevant(VideoContext context) {
        boolean shortVideo = isShortVideo(context);
        if (context.analysisMode() == AnalysisMode.FULL) {
            return selectFullContext(context, shortVideo);
        }

        return shortVideo ? selectShortGoalContext(context) : selectLongGoalContext(context);
    }

    private ContextSelectionResult selectFullContext(VideoContext context, boolean shortVideo) {
        if (shortVideo) {
            int selectedCount = context.segments().isEmpty() ? 0 : 1;
            logSelection(AnalysisMode.FULL, VideoType.SHORT, "N/A", "N/A",
                    selectedCount, selectedCount);
            return ContextSelectionResult.matched(context);
        }

        List<VideoChunk> chunks = summarizeChunks(context.segments());
        List<VideoContext.VideoSegment> summarySegments = chunks.stream()
                .sorted(Comparator.comparingLong(VideoChunk::startTime))
                .map(this::toSummarySegment)
                .toList();
        logSelection(AnalysisMode.FULL, VideoType.LONG, "N/A", "N/A",
                chunks.size(), chunks.size());
        return ContextSelectionResult.matched(new VideoContext(
                context.source(), context.analysisMode(), context.userGoal(), summarySegments));
    }

    private ContextSelectionResult selectShortGoalContext(VideoContext context) {
        double threshold = relevanceProperties.getShortThreshold();
        String searchableText = shortVideoSearchableText(context);
        if (searchableText.isBlank()) {
            log.warn("Empty relevance input mode={} videoType={}",
                    AnalysisMode.GOAL, VideoType.SHORT);
            logSelection(AnalysisMode.GOAL, VideoType.SHORT, 0.0, threshold, 0, 0);
            return ContextSelectionResult.noMatch(context);
        }

        List<Double> queryEmbedding = embeddingUtils.embed(context.userGoal());
        List<Double> contextEmbedding = embeddingUtils.embed(searchableText);
        double similarity = cosine(
                queryEmbedding, contextEmbedding, AnalysisMode.GOAL, VideoType.SHORT);
        boolean matched = similarity >= threshold;
        int matchCount = matched ? 1 : 0;
        logSelection(AnalysisMode.GOAL, VideoType.SHORT, similarity, threshold,
                matchCount, matchCount);
        return matched
                ? ContextSelectionResult.matched(context)
                : ContextSelectionResult.noMatch(context);
    }

    private ContextSelectionResult selectLongGoalContext(VideoContext context) {
        double threshold = relevanceProperties.getLongThreshold();
        List<VideoChunk> chunks = summarizeChunks(context.segments());
        List<VideoChunk> embeddedChunks = chunks.stream()
                .map(this::withEmbedding)
                .toList();
        List<Double> queryEmbedding = embeddingUtils.embed(context.userGoal());

        List<ScoredChunk> scoredChunks = embeddedChunks.stream()
                .map(chunk -> new ScoredChunk(
                        chunk,
                        cosine(queryEmbedding, chunk.embedding(),
                                AnalysisMode.GOAL, VideoType.LONG)))
                .toList();
        double maxSimilarity = scoredChunks.stream()
                .mapToDouble(ScoredChunk::similarity)
                .max()
                .orElse(0.0);

        List<ScoredChunk> matchedChunks = scoredChunks.stream()
                .filter(scored -> scored.similarity() >= threshold)
                .toList();
        List<ScoredChunk> selectedChunks = matchedChunks.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::similarity)
                        .reversed()
                        .thenComparingLong(scored -> scored.chunk().startTime()))
                .limit(relevanceProperties.getTopK())
                .toList();

        logSelection(AnalysisMode.GOAL, VideoType.LONG, maxSimilarity, threshold,
                matchedChunks.size(), selectedChunks.size());
        if (selectedChunks.isEmpty()) {
            return ContextSelectionResult.noMatch(context);
        }

        List<VideoContext.VideoSegment> selectedSegments = selectedChunks.stream()
                .map(ScoredChunk::chunk)
                .flatMap(chunk -> chunk.rawSegments().stream())
                .sorted(Comparator.comparingLong(VideoContext.VideoSegment::startMs))
                .toList();

        return ContextSelectionResult.matched(new VideoContext(
                context.source(), context.analysisMode(), context.userGoal(), selectedSegments));
    }

    private boolean isShortVideo(VideoContext context) {
        return context.segments().isEmpty()
                || context.segments().get(context.segments().size() - 1).endMs() <= CHUNK_MS;
    }

    private String shortVideoSearchableText(VideoContext context) {
        return context.segments().stream()
                .flatMap(segment -> Stream.concat(
                        "ASR".equals(segment.contentSource())
                                && segment.transcript() != null
                                && !segment.transcript().isBlank()
                                ? Stream.of(segment.transcript().trim())
                                : Stream.empty(),
                        segment.ocrTexts() == null
                                ? Stream.empty()
                                : segment.ocrTexts().stream()
                                .filter(text -> text != null && !text.isBlank())
                                .map(String::trim)
                ))
                .collect(joining("\n"));
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

    private double cosine(List<Double> left,
                          List<Double> right,
                          AnalysisMode mode,
                          VideoType videoType) {
        if (left == null || right == null
                || left.isEmpty() || right.isEmpty()
                || left.size() != right.size()) {
            log.warn("Invalid relevance embedding mode={} videoType={}", mode, videoType);
            return 0;
        }

        double dot = 0;
        double leftLength = 0;
        double rightLength = 0;
        for (int i = 0; i < left.size(); i++) {
            Double leftValue = left.get(i);
            Double rightValue = right.get(i);
            if (leftValue == null || rightValue == null
                    || !Double.isFinite(leftValue) || !Double.isFinite(rightValue)) {
                log.warn("Invalid relevance embedding mode={} videoType={}", mode, videoType);
                return 0;
            }
            dot += leftValue * rightValue;
            leftLength += leftValue * leftValue;
            rightLength += rightValue * rightValue;
        }
        if (leftLength == 0 || rightLength == 0) {
            log.warn("Invalid relevance embedding mode={} videoType={}", mode, videoType);
            return 0;
        }
        return dot / (Math.sqrt(leftLength) * Math.sqrt(rightLength));
    }

    private void logSelection(AnalysisMode mode,
                              VideoType videoType,
                              Object maxSimilarity,
                              Object threshold,
                              int matchedCount,
                              int selectedCount) {
        log.info("Video context selection mode={} videoType={} maxSimilarity={} threshold={} "
                        + "matchedCount={} selectedCount={}",
                mode, videoType, maxSimilarity, threshold, matchedCount, selectedCount);
    }

    private record ScoredChunk(VideoChunk chunk, double similarity) {
    }

    private enum VideoType {
        SHORT,
        LONG
    }
}
