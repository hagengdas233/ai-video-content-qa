package com.example.server.controller;

import com.example.server.auth.UserContext;
import com.example.server.entity.KnowledgeDocument;
import com.example.server.service.KnowledgeDocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        try {
            Long currentUserId = UserContext.requireUserId();
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
    public List<KnowledgeDocument> list() {
        return knowledgeDocumentService.listByUser(UserContext.requireUserId());
    }

    @DeleteMapping("/document/{documentId}")
    public ResponseEntity<?> delete(@PathVariable Long documentId) {
        try {
            knowledgeDocumentService.deleteDocument(documentId, UserContext.requireUserId());
            return ResponseEntity.ok("删除成功");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Delete failed: " + e.getMessage());
        }
    }

}
