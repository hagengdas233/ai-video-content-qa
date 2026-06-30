package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.KnowledgeChunk;
import com.example.server.mapper.KnowledgeChunkMapper;
import com.example.server.utils.EmbeddingUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeRetrievalService {

    private static final int DEFAULT_TOP_K = 5;

    @Autowired
    private KnowledgeChunkMapper knowledgeChunkMapper;

    @Autowired
    private EmbeddingUtils embeddingUtils;

    public List<Map<String, Object>> search(Long userId, String question, Integer topK) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }

        int limit = topK == null || topK <= 0 ? DEFAULT_TOP_K : topK;
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
                .limit(limit)
                .toList();
    }

    private List<KnowledgeChunk> listChunksWithEmbedding(Long userId) {
        QueryWrapper<KnowledgeChunk> query = new QueryWrapper<>();
        query.eq("user_id", userId)
                .isNotNull("embedding")
                .ne("embedding", "")
                .orderByAsc("document_id")
                .orderByAsc("chunk_index");
        return knowledgeChunkMapper.selectList(query);
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
