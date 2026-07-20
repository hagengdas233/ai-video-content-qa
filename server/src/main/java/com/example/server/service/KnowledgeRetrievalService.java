package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.example.server.entity.KnowledgeChunk;
import com.example.server.mapper.KnowledgeChunkMapper;
import com.example.server.utils.EmbeddingUtils;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeRetrievalService {

    public static final int DEFAULT_TOP_K = 5;
    public static final int MIN_TOP_K = 1;
    public static final int MAX_TOP_K = 20;
    public static final double MIN_SCORE = 0.45;

    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final EmbeddingUtils embeddingUtils;

    public KnowledgeRetrievalService(KnowledgeChunkMapper knowledgeChunkMapper,
                                     EmbeddingUtils embeddingUtils) {
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.embeddingUtils = embeddingUtils;
    }

    public List<Map<String, Object>> search(Long userId, String question, Integer topK) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }

        int limit = validateTopK(topK);
        List<KnowledgeChunk> chunks = listChunksWithEmbedding(userId);
        if (chunks.isEmpty()) {
            return List.of();
        }

        List<Double> questionEmbedding = embeddingUtils.embed(question);
        return chunks.stream()
                .map(chunk -> toScoredResult(chunk, questionEmbedding))
                .sorted((left, right) -> Double.compare(
                        (Double) right.get("score"),
                        (Double) left.get("score")
                ))
                .filter(result -> (Double) result.get("score") >= MIN_SCORE)
                .limit(limit)
                .toList();
    }

    private List<KnowledgeChunk> listChunksWithEmbedding(Long userId) {
        return knowledgeChunkMapper.selectRetrievableByUser(userId);
    }

    public static int validateTopK(Integer topK) {
        if (topK == null) {
            return DEFAULT_TOP_K;
        }
        if (topK < MIN_TOP_K || topK > MAX_TOP_K) {
            throw new IllegalArgumentException("topK must be between 1 and 20");
        }
        return topK;
    }

    private Map<String, Object> toScoredResult(KnowledgeChunk chunk, List<Double> questionEmbedding) {
        List<Double> chunkEmbedding = JSON.parseArray(chunk.getEmbedding(), Double.class);
        double score = cosine(questionEmbedding, chunkEmbedding);

        Map<String, Object> result = new HashMap<>();
        result.put("chunkId", chunk.getId());
        result.put("documentId", chunk.getDocumentId());
        result.put("chunkIndex", chunk.getChunkIndex());
        result.put("content", chunk.getContent());
        result.put("score", score);
        return result;
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.size() != right.size() || left.isEmpty()) {
            return 0;
        }

        double dot = 0;
        double leftLength = 0;
        double rightLength = 0;
        for (int i = 0; i < left.size(); i++) {
            dot += left.get(i) * right.get(i);
            leftLength += left.get(i) * left.get(i);
            rightLength += right.get(i) * right.get(i);
        }
        if (leftLength == 0 || rightLength == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftLength) * Math.sqrt(rightLength));
    }
}
