package com.example.server.controller;

import com.example.server.auth.UserContext;
import com.example.server.service.KnowledgeRetrievalService;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private KnowledgeRetrievalService knowledgeRetrievalService;

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
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Search failed: " + e.getMessage());
        }
    }

    private Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        return Integer.valueOf(String.valueOf(value));
    }
}
