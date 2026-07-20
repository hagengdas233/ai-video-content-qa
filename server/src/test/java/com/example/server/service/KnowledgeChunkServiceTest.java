package com.example.server.service;

import com.example.server.entity.KnowledgeChunk;
import com.example.server.utils.EmbeddingUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeChunkServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {" ", "\n\t", "\uFEFF", "\uFEFF \n\t"})
    void blankOrBomOnlyContentIsRejectedBeforeEmbedding(String content) {
        EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
        KnowledgeChunkService service = new KnowledgeChunkService(embeddingUtils, "test-model");

        assertThrows(IllegalArgumentException.class,
                () -> service.prepareChunks(10L, 20L, content));

        verifyNoInteractions(embeddingUtils);
    }

    @Test
    void nthEmbeddingFailureProducesNoPreparedChunkListOrDatabaseWrites() {
        EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
        KnowledgeChunkService service = new KnowledgeChunkService(embeddingUtils, "test-model");
        when(embeddingUtils.embed(anyString()))
                .thenReturn(List.of(1.0, 0.0))
                .thenThrow(new IllegalStateException("mock embedding failure"));

        String content = "a".repeat(1000) + "b".repeat(500);

        assertThrows(IllegalStateException.class,
                () -> service.prepareChunks(10L, 20L, content));
        verify(embeddingUtils, times(2)).embed(anyString());
    }

    @Test
    void allEmbeddingsArePreparedInMemoryWithStableIndexes() {
        EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
        KnowledgeChunkService service = new KnowledgeChunkService(embeddingUtils, "test-model");
        when(embeddingUtils.embed(anyString())).thenReturn(List.of(1.0, 0.0));

        List<KnowledgeChunk> chunks = service.prepareChunks(
                10L, 20L, "a".repeat(1000) + "b".repeat(500));

        assertEquals(2, chunks.size());
        assertEquals(0, chunks.get(0).getChunkIndex());
        assertEquals(1, chunks.get(1).getChunkIndex());
        assertEquals(10L, chunks.get(0).getDocumentId());
        assertEquals(20L, chunks.get(0).getUserId());
        assertEquals("test-model", chunks.get(0).getEmbeddingModel());
        verify(embeddingUtils, times(2)).embed(anyString());
    }
}
