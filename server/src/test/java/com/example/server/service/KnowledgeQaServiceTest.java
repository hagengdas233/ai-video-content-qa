package com.example.server.service;

import com.example.server.utils.DeepSeekUtils;
import com.example.server.mapper.KnowledgeChunkMapper;
import com.example.server.utils.EmbeddingUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeQaServiceTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 21, Integer.MAX_VALUE})
    void illegalTopKStopsBeforeRetrievalEmbeddingOrModel(int topK) {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
        DeepSeekUtils deepSeekUtils = mock(DeepSeekUtils.class);
        KnowledgeRetrievalService retrievalService = new KnowledgeRetrievalService(mapper, embeddingUtils);
        KnowledgeQaService service = new KnowledgeQaService(retrievalService, deepSeekUtils);

        assertThrows(IllegalArgumentException.class,
                () -> service.ask(20L, "question", topK));

        verifyNoInteractions(mapper, embeddingUtils, deepSeekUtils);
    }

    @Test
    void sourcesAreExactlyTheFinalConstrainedRetrievalResults() {
        KnowledgeRetrievalService retrievalService = mock(KnowledgeRetrievalService.class);
        DeepSeekUtils deepSeekUtils = mock(DeepSeekUtils.class);
        KnowledgeQaService service = new KnowledgeQaService(retrievalService, deepSeekUtils);
        List<Map<String, Object>> legalSources = List.of(Map.of(
                "chunkId", 1L,
                "documentId", 10L,
                "chunkIndex", 0,
                "content", "legal source",
                "score", 0.9));
        when(retrievalService.search(20L, "question", 5)).thenReturn(legalSources);
        when(deepSeekUtils.chat(anyString())).thenReturn("answer");

        Map<String, Object> result = service.ask(20L, "question", 5);

        assertEquals("answer", result.get("answer"));
        assertEquals(legalSources, result.get("sources"));
        verify(retrievalService).search(20L, "question", 5);
    }
}
