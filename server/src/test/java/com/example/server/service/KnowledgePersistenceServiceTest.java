package com.example.server.service;

import com.example.server.entity.KnowledgeChunk;
import com.example.server.entity.KnowledgeDocument;
import com.example.server.mapper.KnowledgeChunkMapper;
import com.example.server.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgePersistenceServiceTest {

    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @Mock
    private KnowledgeChunkMapper chunkMapper;

    private KnowledgePersistenceService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgePersistenceService(documentMapper, chunkMapper);
    }

    @Test
    void successfulFinalizeCleansThenBatchInsertsAndMarksReady() {
        List<KnowledgeChunk> chunks = List.of(chunk(0), chunk(1));
        when(documentMapper.selectOwnedById(10L, 20L)).thenReturn(document("PROCESSING", 0));
        when(chunkMapper.deleteByDocumentAndUser(10L, 20L)).thenReturn(0);
        when(chunkMapper.countByDocumentAndUser(10L, 20L)).thenReturn(0);
        when(chunkMapper.insertBatch(chunks)).thenReturn(2);
        when(documentMapper.markReadyIfProcessing(eq(10L), eq(20L), eq(2), any())).thenReturn(1);

        assertEquals(2, service.persistPreparedChunks(10L, 20L, chunks));

        verify(chunkMapper).insertBatch(chunks);
        verify(documentMapper).markReadyIfProcessing(eq(10L), eq(20L), eq(2), any());
    }

    @Test
    void partialBatchResultThrowsInsideTransactionalBoundary() throws Exception {
        List<KnowledgeChunk> chunks = List.of(chunk(0), chunk(1));
        when(documentMapper.selectOwnedById(10L, 20L)).thenReturn(document("PROCESSING", 0));
        when(chunkMapper.deleteByDocumentAndUser(10L, 20L)).thenReturn(0);
        when(chunkMapper.countByDocumentAndUser(10L, 20L)).thenReturn(0);
        when(chunkMapper.insertBatch(chunks)).thenReturn(1);

        assertThrows(IllegalStateException.class,
                () -> service.persistPreparedChunks(10L, 20L, chunks));
        verify(documentMapper, never()).markReadyIfProcessing(any(), any(), anyInt(), any());
        assertTrue(KnowledgePersistenceService.class
                .getMethod("persistPreparedChunks", Long.class, Long.class, List.class)
                .isAnnotationPresent(Transactional.class));
    }

    @Test
    void rejectedReadyTransitionThrowsSoTransactionRollsBackBatch() {
        List<KnowledgeChunk> chunks = List.of(chunk(0), chunk(1));
        when(documentMapper.selectOwnedById(10L, 20L)).thenReturn(document("PROCESSING", 0));
        when(chunkMapper.deleteByDocumentAndUser(10L, 20L)).thenReturn(0);
        when(chunkMapper.countByDocumentAndUser(10L, 20L)).thenReturn(0);
        when(chunkMapper.insertBatch(chunks)).thenReturn(2);
        when(documentMapper.markReadyIfProcessing(eq(10L), eq(20L), eq(2), any())).thenReturn(0);

        assertThrows(KnowledgePersistenceService.ProcessingStateException.class,
                () -> service.persistPreparedChunks(10L, 20L, chunks));
        verify(chunkMapper).insertBatch(chunks);
    }

    @Test
    void failureCleanupDeletesChunksAndConditionallyMarksFailedWithZeroCount() {
        when(chunkMapper.deleteByDocumentAndUser(10L, 20L)).thenReturn(3);
        when(chunkMapper.countByDocumentAndUser(10L, 20L)).thenReturn(0);
        when(documentMapper.markFailedIfProcessing(eq(10L), eq(20L), anyString(), any()))
                .thenReturn(1);

        assertTrue(service.markFailed(
                10L, 20L, KnowledgePersistenceService.ERROR_CHUNK_PREPARATION));

        verify(chunkMapper).deleteByDocumentAndUser(10L, 20L);
        verify(documentMapper).markFailedIfProcessing(
                eq(10L), eq(20L),
                eq(KnowledgePersistenceService.ERROR_CHUNK_PREPARATION), any());
    }

    @Test
    void deletedDocumentIsNotRecreatedDuringFailureCleanup() {
        when(chunkMapper.deleteByDocumentAndUser(10L, 20L)).thenReturn(0);
        when(chunkMapper.countByDocumentAndUser(10L, 20L)).thenReturn(0);
        when(documentMapper.markFailedIfProcessing(eq(10L), eq(20L), anyString(), any()))
                .thenReturn(0);
        when(documentMapper.selectOwnedById(10L, 20L)).thenReturn(null);

        assertFalse(service.markFailed(10L, 20L, "safe summary"));

        verify(documentMapper, never()).insert(any(KnowledgeDocument.class));
        verify(documentMapper, never()).updateById(any(KnowledgeDocument.class));
    }

    @Test
    void failedTransitionRejectedByChangedStatusThrowsAndDoesNotRewriteDocument() {
        when(chunkMapper.deleteByDocumentAndUser(10L, 20L)).thenReturn(0);
        when(chunkMapper.countByDocumentAndUser(10L, 20L)).thenReturn(0);
        when(documentMapper.markFailedIfProcessing(eq(10L), eq(20L), anyString(), any()))
                .thenReturn(0);
        when(documentMapper.selectOwnedById(10L, 20L)).thenReturn(document("READY", 1));

        assertThrows(KnowledgePersistenceService.ProcessingStateException.class,
                () -> service.markFailed(
                        10L, 20L, KnowledgePersistenceService.ERROR_PERSISTENCE));

        verify(documentMapper, never()).updateById(any(KnowledgeDocument.class));
    }

    @Test
    void mismatchedPreparedChunkIsRejectedBeforeAnyDatabaseMutation() {
        KnowledgeChunk mismatched = chunk(0);
        mismatched.setUserId(99L);

        assertThrows(IllegalArgumentException.class,
                () -> service.persistPreparedChunks(10L, 20L, List.of(mismatched)));

        verify(chunkMapper, never()).deleteByDocumentAndUser(any(), any());
        verify(chunkMapper, never()).insertBatch(anyList());
    }

    @Test
    void nullOrEmptyPreparedChunksAreRejectedBeforeAnyDatabaseMutation() {
        assertThrows(IllegalArgumentException.class,
                () -> service.persistPreparedChunks(10L, 20L, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.persistPreparedChunks(10L, 20L, List.of()));

        verify(documentMapper, never()).selectOwnedById(any(), any());
        verify(chunkMapper, never()).deleteByDocumentAndUser(any(), any());
        verify(chunkMapper, never()).insertBatch(anyList());
        verify(documentMapper, never()).markReadyIfProcessing(any(), any(), anyInt(), any());
    }

    @Test
    void untrustedFailureDetailsAreReplacedBySafeSummaryBeforeConditionalUpdate() {
        when(chunkMapper.deleteByDocumentAndUser(10L, 20L)).thenReturn(0);
        when(chunkMapper.countByDocumentAndUser(10L, 20L)).thenReturn(0);
        when(documentMapper.markFailedIfProcessing(eq(10L), eq(20L), anyString(), any()))
                .thenReturn(1);
        ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);

        assertTrue(service.markFailed(10L, 20L,
                "endpoint=https://vendor bucket=secret path=C:\\private third-party-response="
                        + "x".repeat(800)));

        verify(documentMapper).markFailedIfProcessing(
                eq(10L), eq(20L), summaryCaptor.capture(), any());
        assertEquals("Document processing failed", summaryCaptor.getValue());
    }

    @Test
    void incompleteCleanupPreventsBatchAndStateTransition() {
        when(documentMapper.selectOwnedById(10L, 20L)).thenReturn(document("PROCESSING", 0));
        when(chunkMapper.deleteByDocumentAndUser(10L, 20L)).thenReturn(1);
        when(chunkMapper.countByDocumentAndUser(10L, 20L)).thenReturn(1);

        assertThrows(IllegalStateException.class,
                () -> service.persistPreparedChunks(10L, 20L, List.of(chunk(0))));

        verify(chunkMapper, never()).insertBatch(anyList());
        verify(documentMapper, never()).markReadyIfProcessing(any(), any(), anyInt(), any());
    }

    private KnowledgeChunk chunk(int index) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setDocumentId(10L);
        chunk.setUserId(20L);
        chunk.setChunkIndex(index);
        return chunk;
    }

    private KnowledgeDocument document(String status, int chunkCount) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(10L);
        document.setUserId(20L);
        document.setStatus(status);
        document.setChunkCount(chunkCount);
        return document;
    }
}
