package com.example.server.controller;

import com.example.server.auth.UserContext;
import com.example.server.service.KnowledgeQaService;
import com.example.server.service.KnowledgeRetrievalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeTopKControllerTest {

    private final KnowledgeRetrievalService retrievalService = mock(KnowledgeRetrievalService.class);
    private final KnowledgeQaService qaService = mock(KnowledgeQaService.class);
    private final KnowledgeRetrievalController retrievalController =
            new KnowledgeRetrievalController(retrievalService);
    private final KnowledgeQaController qaController = new KnowledgeQaController(qaService);

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void missingTopKIsPassedAsNullForSharedDefaultHandling() {
        UserContext.setUserId(20L);
        when(retrievalService.search(20L, "question", null)).thenReturn(List.of());
        when(qaService.ask(20L, "question", null)).thenReturn(Map.of("answer", "none", "sources", List.of()));

        assertEquals(200, retrievalController.search(Map.of("question", "question"))
                .getStatusCode().value());
        assertEquals(200, qaController.ask(Map.of("question", "question"))
                .getStatusCode().value());

        verify(retrievalService).search(20L, "question", null);
        verify(qaService).ask(20L, "question", null);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 21, Integer.MAX_VALUE})
    void illegalTopKIsReportedAsBadRequestByBothEndpoints(int topK) {
        UserContext.setUserId(20L);
        when(retrievalService.search(20L, "question", topK))
                .thenThrow(new IllegalArgumentException("topK must be between 1 and 20"));
        when(qaService.ask(20L, "question", topK))
                .thenThrow(new IllegalArgumentException("topK must be between 1 and 20"));

        ResponseEntity<?> searchResponse = retrievalController.search(
                Map.of("question", "question", "topK", topK));
        ResponseEntity<?> askResponse = qaController.ask(
                Map.of("question", "question", "topK", topK));

        assertEquals(400, searchResponse.getStatusCode().value());
        assertEquals(400, askResponse.getStatusCode().value());
        assertEquals(KnowledgeRetrievalController.INVALID_REQUEST_MESSAGE, searchResponse.getBody());
        assertEquals(KnowledgeQaController.INVALID_REQUEST_MESSAGE, askResponse.getBody());
    }

    @Test
    void unexpectedFailuresDoNotExposeSdkOrInfrastructureDetails() {
        UserContext.setUserId(20L);
        String sensitive = "endpoint=https://vendor bucket=secret path=C:\\private response=credential";
        when(retrievalService.search(20L, "question", 5))
                .thenThrow(new IllegalStateException(sensitive));
        when(qaService.ask(20L, "question", 5))
                .thenThrow(new IllegalStateException(sensitive));

        String searchBody = String.valueOf(retrievalController.search(
                Map.of("question", "question", "topK", 5)).getBody());
        String askBody = String.valueOf(qaController.ask(
                Map.of("question", "question", "topK", 5)).getBody());

        assertEquals(KnowledgeRetrievalController.SEARCH_FAILURE_MESSAGE, searchBody);
        assertEquals(KnowledgeQaController.ASK_FAILURE_MESSAGE, askBody);
        assertFalse(searchBody.contains("endpoint"));
        assertFalse(askBody.contains("credential"));
    }
}
