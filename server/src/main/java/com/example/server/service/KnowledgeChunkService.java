package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.example.server.entity.KnowledgeChunk;
import com.example.server.utils.EmbeddingUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
public class KnowledgeChunkService {

    private static final int CHUNK_SIZE = 1000;
    private static final int OVERLAP = 150;

    private final EmbeddingUtils embeddingUtils;

    private final String embeddingModel;

    public KnowledgeChunkService(EmbeddingUtils embeddingUtils,
                                 @Value("${ai.embedding.model:BAAI/bge-m3}") String embeddingModel) {
        this.embeddingUtils = embeddingUtils;
        this.embeddingModel = embeddingModel;
    }

    public List<KnowledgeChunk> prepareChunks(Long documentId, Long userId, String content) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId is required");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        String normalizedContent = stripLeadingBom(content);
        if (normalizedContent == null || normalizedContent.isBlank()) {
            throw new IllegalArgumentException("document contains no valid text");
        }

        List<KnowledgeChunk> chunks = new ArrayList<>();
        int chunkCount = 0;
        int start = 0;
        while (start < normalizedContent.length()) {
            int end = Math.min(start + CHUNK_SIZE, normalizedContent.length());
            String chunkText = normalizedContent.substring(start, end).trim();

            if (!chunkText.isBlank()) {
                List<Double> vector = embeddingUtils.embed(chunkText);
                if (vector == null || vector.isEmpty()) {
                    throw new IllegalStateException("Embedding result is empty");
                }

                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.setDocumentId(documentId);
                chunk.setUserId(userId);
                chunk.setChunkIndex(chunkCount);
                chunk.setContent(chunkText);
                chunk.setContentHash(sha256(chunkText));
                chunk.setCharCount(chunkText.length());
                chunk.setEmbedding(JSON.toJSONString(vector));
                chunk.setEmbeddingModel(embeddingModel);
                chunk.setEmbeddingDim(vector.size());
                chunks.add(chunk);
                chunkCount++;
            }

            if (end >= normalizedContent.length()) {
                break;
            }
            start = Math.max(end - OVERLAP, start + 1);
        }

        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("document contains no valid text");
        }
        return List.copyOf(chunks);
    }

    private String stripLeadingBom(String content) {
        if (content != null && content.startsWith("\uFEFF")) {
            return content.substring(1);
        }
        return content;
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
