package com.example.server.service;

import com.example.server.utils.DeepSeekUtils;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeQaService {

    private static final String NO_ANSWER = "知识库中没有找到相关信息";

    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final DeepSeekUtils deepSeekUtils;

    public KnowledgeQaService(KnowledgeRetrievalService knowledgeRetrievalService,
                              DeepSeekUtils deepSeekUtils) {
        this.knowledgeRetrievalService = knowledgeRetrievalService;
        this.deepSeekUtils = deepSeekUtils;
    }

    public Map<String, Object> ask(Long userId, String question, Integer topK) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }

        List<Map<String, Object>> sources = knowledgeRetrievalService.search(userId, question, topK)
                .stream()
                .filter(this::isRelevant)
                .toList();
        String answer;
        if (sources.isEmpty()) {
            answer = NO_ANSWER;
        } else {
            answer = deepSeekUtils.chat(buildPrompt(question, sources));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("answer", answer);
        result.put("sources", sources);
        return result;
    }

    private boolean isRelevant(Map<String, Object> source) {
        Object score = source.get("score");
        return score instanceof Number number && number.doubleValue() >= KnowledgeRetrievalService.MIN_SCORE;
    }

    private String buildPrompt(String question, List<Map<String, Object>> sources) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            Map<String, Object> source = sources.get(i);
            context.append("【片段").append(i + 1).append("】\n")
                    .append("chunkId: ").append(source.get("chunkId")).append("\n")
                    .append("documentId: ").append(source.get("documentId")).append("\n")
                    .append("chunkIndex: ").append(source.get("chunkIndex")).append("\n")
                    .append("score: ").append(source.get("score")).append("\n")
                    .append("content:\n").append(source.get("content")).append("\n\n");
        }

        return """
                你是一个知识库问答助手。
                请只能基于给定上下文回答用户问题。
                如果上下文中找不到答案，请只回答“知识库中没有找到相关信息”。
                不要编造，不要使用上下文以外的知识。

                上下文：
                %s

                用户问题：
                %s
                """.formatted(context, question);
    }
}
