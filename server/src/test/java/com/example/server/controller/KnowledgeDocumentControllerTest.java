package com.example.server.controller;

import com.example.server.auth.UserContext;
import com.example.server.service.KnowledgeDocumentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeDocumentControllerTest {

    private final KnowledgeDocumentService service = mock(KnowledgeDocumentService.class);
    private final KnowledgeDocumentController controller = new KnowledgeDocumentController(service);

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void uploadProcessingFailureCannotReturnFalseSuccess() {
        UserContext.setUserId(20L);
        MockMultipartFile file = new MockMultipartFile("file", "doc.txt", "text/plain", "text".getBytes());
        when(service.uploadAndProcess(any(), eq(20L))).thenThrow(
                new KnowledgeDocumentService.DocumentProcessingException(
                        "Document processing failed", new IllegalStateException("mock failure")));

        ResponseEntity<?> response = controller.upload(file);

        assertEquals(500, response.getStatusCode().value());
        assertEquals(KnowledgeDocumentController.UPLOAD_FAILURE_MESSAGE, response.getBody());
    }

    @Test
    void uploadResponseNeverExposesSensitiveNestedFailureDetails() {
        UserContext.setUserId(20L);
        MockMultipartFile file = new MockMultipartFile("file", "doc.txt", "text/plain", "text".getBytes());
        String sensitive = "endpoint=https://vendor bucket=secret path=C:\\private response=credential";
        when(service.uploadAndProcess(any(), eq(20L))).thenThrow(
                new KnowledgeDocumentService.DocumentProcessingException(
                        "Document processing failed", new IllegalStateException(sensitive)));

        ResponseEntity<?> response = controller.upload(file);

        assertEquals(500, response.getStatusCode().value());
        String body = String.valueOf(response.getBody());
        assertEquals(KnowledgeDocumentController.UPLOAD_FAILURE_MESSAGE, body);
        assertFalse(body.contains("endpoint"));
        assertFalse(body.contains("bucket"));
        assertFalse(body.contains("private"));
        assertFalse(body.contains("credential"));
    }

    @Test
    void processingDeleteConflictReturns409() {
        UserContext.setUserId(20L);
        org.mockito.Mockito.doThrow(new KnowledgeDocumentService.DocumentConflictException(
                        "PROCESSING document cannot be deleted"))
                .when(service).deleteDocument(10L, 20L);

        ResponseEntity<?> response = controller.delete(10L);

        assertEquals(409, response.getStatusCode().value());
    }
}
