package com.example.server.controller;

import com.example.server.auth.UserContext;
import com.example.server.entity.KnowledgeDocument;
import com.example.server.service.KnowledgeDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    static final String UPLOAD_FAILURE_MESSAGE = "文档上传处理失败，请稍后重试";
    static final String DELETE_FAILURE_MESSAGE = "文档删除失败，请稍后重试";
    static final String INVALID_REQUEST_MESSAGE = "请求参数不合法";
    static final String DOCUMENT_NOT_FOUND_MESSAGE = "知识库文档不存在";
    static final String DELETE_CONFLICT_MESSAGE = "文档正在处理中或状态已变化，暂不可删除";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentController.class);

    private final KnowledgeDocumentService knowledgeDocumentService;

    public KnowledgeDocumentController(KnowledgeDocumentService knowledgeDocumentService) {
        this.knowledgeDocumentService = knowledgeDocumentService;
    }

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
        } catch (KnowledgeDocumentService.DocumentProcessingException e) {
            logFailure("Knowledge document processing failed", e);
            return ResponseEntity.status(500).body(UPLOAD_FAILURE_MESSAGE);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(INVALID_REQUEST_MESSAGE);
        } catch (Exception e) {
            logFailure("Knowledge document upload failed", e);
            return ResponseEntity.status(500).body(UPLOAD_FAILURE_MESSAGE);
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
        } catch (KnowledgeDocumentService.DocumentNotFoundException e) {
            return ResponseEntity.status(404).body(DOCUMENT_NOT_FOUND_MESSAGE);
        } catch (KnowledgeDocumentService.DocumentConflictException e) {
            return ResponseEntity.status(409).body(DELETE_CONFLICT_MESSAGE);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(INVALID_REQUEST_MESSAGE);
        } catch (Exception e) {
            logFailure("Knowledge document deletion failed", e);
            return ResponseEntity.status(500).body(DELETE_FAILURE_MESSAGE);
        }
    }

    private void logFailure(String event, Exception exception) {
        Throwable cause = exception.getCause();
        log.error("{}; exceptionType={}; causeType={}", event,
                exception.getClass().getName(),
                cause == null ? "none" : cause.getClass().getName());
    }

}
