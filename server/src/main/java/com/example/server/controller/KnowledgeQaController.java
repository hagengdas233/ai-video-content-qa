package com.example.server.controller;

import com.example.server.auth.UserContext;
import com.example.server.service.KnowledgeQaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/knowledge")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class KnowledgeQaController {

    static final String ASK_FAILURE_MESSAGE = "知识库问答失败，请稍后重试";
    static final String INVALID_REQUEST_MESSAGE = "请求参数不合法";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQaController.class);
    private final KnowledgeQaService knowledgeQaService;

    public KnowledgeQaController(KnowledgeQaService knowledgeQaService) {
        this.knowledgeQaService = knowledgeQaService;
    }

    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody Map<String, Object> request) {
        try {
            if (request == null) {
                return ResponseEntity.badRequest().body("request body is required");
            }
            String question = request.get("question") == null ? null : String.valueOf(request.get("question"));
            Integer topK = parseInteger(request.get("topK"));
            return ResponseEntity.ok(knowledgeQaService.ask(UserContext.requireUserId(), question, topK));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(INVALID_REQUEST_MESSAGE);
        } catch (Exception e) {
            log.error("Knowledge question answering failed; exceptionType={}", e.getClass().getName());
            return ResponseEntity.status(500).body(ASK_FAILURE_MESSAGE);
        }
    }

    private Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        return Integer.valueOf(String.valueOf(value));
    }
}
