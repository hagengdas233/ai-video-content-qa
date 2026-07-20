package com.example.server.controller;

import com.example.server.auth.UserContext;
import com.example.server.service.KnowledgeRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/knowledge")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class KnowledgeRetrievalController {

    static final String SEARCH_FAILURE_MESSAGE = "知识库检索失败，请稍后重试";
    static final String INVALID_REQUEST_MESSAGE = "请求参数不合法";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalController.class);
    private final KnowledgeRetrievalService knowledgeRetrievalService;

    public KnowledgeRetrievalController(KnowledgeRetrievalService knowledgeRetrievalService) {
        this.knowledgeRetrievalService = knowledgeRetrievalService;
    }

    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody Map<String, Object> request) {
        try {
            if (request == null) {
                return ResponseEntity.badRequest().body("request body is required");
            }
            String question = request.get("question") == null ? null : String.valueOf(request.get("question"));
            Integer topK = parseInteger(request.get("topK"));
            List<Map<String, Object>> results = knowledgeRetrievalService.search(
                    UserContext.requireUserId(), question, topK);
            return ResponseEntity.ok(results);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(INVALID_REQUEST_MESSAGE);
        } catch (Exception e) {
            log.error("Knowledge retrieval failed; exceptionType={}", e.getClass().getName());
            return ResponseEntity.status(500).body(SEARCH_FAILURE_MESSAGE);
        }
    }

    private Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        return Integer.valueOf(String.valueOf(value));
    }
}
