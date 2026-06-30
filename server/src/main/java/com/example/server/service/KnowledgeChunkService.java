package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.example.server.entity.KnowledgeChunk;
import com.example.server.mapper.KnowledgeChunkMapper;
import com.example.server.utils.EmbeddingUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Service
public class KnowledgeChunkService {

    private static final int CHUNK_SIZE = 1000;
    private static final int OVERLAP = 150;

    @Autowired
    private KnowledgeChunkMapper knowledgeChunkMapper;

    @Autowired
    private EmbeddingUtils embeddingUtils;

    @Value("${ai.embedding.model:BAAI/bge-m3}")
    private String embeddingModel;

    public int splitAndSave(Long documentId, Long userId, String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }

        int chunkCount = 0;
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + CHUNK_SIZE, content.length());
            String chunkText = content.substring(start, end).trim();

            if (!chunkText.isBlank()) {
                List<Double> vector = embeddingUtils.embed(chunkText);

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
                knowledgeChunkMapper.insert(chunk);
                chunkCount++;
            }

            if (end >= content.length()) {
                break;
            }
            start = Math.max(end - OVERLAP, start + 1);
        }

        return chunkCount;
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
