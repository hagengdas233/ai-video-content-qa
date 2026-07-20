package com.example.server.service;

import com.alibaba.fastjson2.JSON;
import com.example.server.entity.KnowledgeChunk;
import com.example.server.mapper.KnowledgeChunkMapper;
import com.example.server.utils.EmbeddingUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalServiceTest {

    @Test
    void missingTopKUsesDefaultFive() {
        TestFixture fixture = fixtureWithLegalChunks(25);

        assertEquals(5, fixture.service().search(20L, "question", null).size());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 20})
    void legalTopKBoundariesLimitResults(int topK) {
        TestFixture fixture = fixtureWithLegalChunks(25);

        assertEquals(topK, fixture.service().search(20L, "question", topK).size());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 21, Integer.MAX_VALUE})
    void illegalTopKStopsBeforeDatabaseOrEmbedding(int topK) {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
        KnowledgeRetrievalService service = new KnowledgeRetrievalService(mapper, embeddingUtils);

        assertThrows(IllegalArgumentException.class,
                () -> service.search(20L, "question", topK));

        verifyNoInteractions(mapper, embeddingUtils);
    }

    @Test
    void searchAndReturnedSourcesUseOnlyTenantSafeMapperResults() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
        KnowledgeRetrievalService service = new KnowledgeRetrievalService(mapper, embeddingUtils);
        KnowledgeChunk legalChunk = new KnowledgeChunk();
        legalChunk.setId(1L);
        legalChunk.setDocumentId(10L);
        legalChunk.setUserId(20L);
        legalChunk.setChunkIndex(0);
        legalChunk.setContent("legal source");
        legalChunk.setEmbedding(JSON.toJSONString(List.of(1.0, 0.0)));
        when(mapper.selectRetrievableByUser(20L)).thenReturn(List.of(legalChunk));
        when(embeddingUtils.embed("question")).thenReturn(List.of(1.0, 0.0));

        List<Map<String, Object>> results = service.search(20L, "question", 5);

        assertEquals(1, results.size());
        assertEquals(1L, results.getFirst().get("chunkId"));
        assertEquals(10L, results.getFirst().get("documentId"));
        assertEquals("legal source", results.getFirst().get("content"));
        verify(mapper).selectRetrievableByUser(20L);
    }

    private TestFixture fixtureWithLegalChunks(int count) {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
        KnowledgeRetrievalService service = new KnowledgeRetrievalService(mapper, embeddingUtils);
        List<KnowledgeChunk> chunks = java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> {
                    KnowledgeChunk chunk = new KnowledgeChunk();
                    chunk.setId((long) index + 1);
                    chunk.setDocumentId(10L);
                    chunk.setUserId(20L);
                    chunk.setChunkIndex(index);
                    chunk.setContent("legal source " + index);
                    chunk.setEmbedding(JSON.toJSONString(List.of(1.0, 0.0)));
                    return chunk;
                })
                .toList();
        when(mapper.selectRetrievableByUser(20L)).thenReturn(chunks);
        when(embeddingUtils.embed("question")).thenReturn(List.of(1.0, 0.0));
        return new TestFixture(service);
    }

    private record TestFixture(KnowledgeRetrievalService service) {
    }
}
