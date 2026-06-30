package com.example.server.controller;

import com.example.server.entity.KnowledgeDocument;
import com.example.server.service.KnowledgeDocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/knowledge")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class KnowledgeDocumentController {

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "userId", required = false) Long userId) {
        try {
            Long currentUserId = resolveUserId(userId);
            KnowledgeDocument document = knowledgeDocumentService.uploadAndProcess(file, currentUserId);

            Map<String, Object> result = new HashMap<>();
            result.put("documentId", document.getId());
            result.put("status", document.getStatus());
            result.put("chunkCount", document.getChunkCount());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Upload failed: " + e.getMessage());
        }
    }

    @GetMapping("/list")
    public List<KnowledgeDocument> list(@RequestParam(value = "userId", required = false) Long userId) {
        Long currentUserId = resolveUserId(userId);
        return knowledgeDocumentService.listByUser(currentUserId);
    }

    private Long resolveUserId(Long userId) {
        // TODO: Replace with the real authenticated user after the project has unified auth.
        return userId == null ? 1L : userId;
    }
}
