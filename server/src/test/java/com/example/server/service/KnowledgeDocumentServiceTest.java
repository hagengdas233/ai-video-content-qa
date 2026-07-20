package com.example.server.service;

import com.example.server.entity.KnowledgeChunk;
import com.example.server.entity.KnowledgeDocument;
import com.example.server.mapper.KnowledgeChunkMapper;
import com.example.server.mapper.KnowledgeDocumentMapper;
import com.example.server.utils.MinioUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentServiceTest {

    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @Mock
    private KnowledgeChunkMapper chunkMapper;
    @Mock
    private KnowledgeChunkService chunkService;
    @Mock
    private KnowledgePersistenceService persistenceService;
    @Mock
    private MinioUtils minioUtils;

    private KnowledgeDocumentService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeDocumentService(
                documentMapper, chunkMapper, chunkService, persistenceService, minioUtils, "media");
    }

    @Test
    void embeddingFailureMarksDocumentFailedAndNeverStartsPersistence() throws Exception {
        MockMultipartFile file = textFile();
        stubDocumentCreation(file);
        when(chunkService.prepareChunks(eq(10L), eq(20L), anyString()))
                .thenThrow(new IllegalStateException("mock embedding failure"));
        when(persistenceService.markFailed(10L, 20L, "Document chunk preparation failed"))
                .thenReturn(true);

        assertThrows(KnowledgeDocumentService.DocumentProcessingException.class,
                () -> service.uploadAndProcess(file, 20L));

        verify(persistenceService, never()).persistPreparedChunks(any(), any(), any());
        verify(persistenceService).markFailed(10L, 20L, "Document chunk preparation failed");
        verifyNoInteractions(chunkMapper);
    }

    @Test
    void zeroByteFileIsRejectedBeforeUploadOrDocumentCreation() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);

        assertThrows(IllegalArgumentException.class,
                () -> service.uploadAndProcess(file, 20L));

        verifyNoInteractions(minioUtils, documentMapper, chunkMapper, chunkService, persistenceService);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "\n\t", "\uFEFF", "\uFEFF \n\t"})
    void extractedBlankOrBomOnlyTextMarksCreatedDocumentFailedWithoutPreparingChunks(String content)
            throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "blank.txt", "text/plain", content.getBytes(StandardCharsets.UTF_8));
        stubDocumentCreation(file);
        when(persistenceService.markFailed(
                10L, 20L, KnowledgePersistenceService.ERROR_TEXT_EXTRACTION)).thenReturn(true);

        assertThrows(KnowledgeDocumentService.DocumentProcessingException.class,
                () -> service.uploadAndProcess(file, 20L));

        verifyNoInteractions(chunkService, chunkMapper);
        verify(persistenceService, never()).persistPreparedChunks(any(), any(), any());
        verify(persistenceService).markFailed(
                10L, 20L, KnowledgePersistenceService.ERROR_TEXT_EXTRACTION);
    }

    @Test
    void sensitiveProcessingFailureIsPreservedAsCauseButOnlySafeStageIsPersisted() throws Exception {
        MockMultipartFile file = textFile();
        stubDocumentCreation(file);
        IllegalStateException sensitiveCause = new IllegalStateException(
                "endpoint=https://vendor bucket=secret path=C:\\private response=credential");
        when(chunkService.prepareChunks(eq(10L), eq(20L), anyString()))
                .thenThrow(sensitiveCause);
        when(persistenceService.markFailed(
                10L, 20L, KnowledgePersistenceService.ERROR_CHUNK_PREPARATION)).thenReturn(true);

        KnowledgeDocumentService.DocumentProcessingException thrown = assertThrows(
                KnowledgeDocumentService.DocumentProcessingException.class,
                () -> service.uploadAndProcess(file, 20L));

        assertSame(sensitiveCause, thrown.getCause());
        verify(persistenceService).markFailed(
                10L, 20L, KnowledgePersistenceService.ERROR_CHUNK_PREPARATION);
    }

    @Test
    void successfulPreparationIsPersistedOnceThenReturnedReady() throws Exception {
        MockMultipartFile file = textFile();
        stubDocumentCreation(file);
        List<KnowledgeChunk> prepared = List.of(chunk(0), chunk(1));
        when(chunkService.prepareChunks(eq(10L), eq(20L), anyString())).thenReturn(prepared);
        when(persistenceService.persistPreparedChunks(10L, 20L, prepared)).thenReturn(2);

        KnowledgeDocument document = service.uploadAndProcess(file, 20L);

        assertEquals("READY", document.getStatus());
        assertEquals(2, document.getChunkCount());
        InOrder order = inOrder(chunkService, persistenceService);
        order.verify(chunkService).prepareChunks(eq(10L), eq(20L), anyString());
        order.verify(persistenceService).persistPreparedChunks(10L, 20L, prepared);
        verifyNoInteractions(chunkMapper);
    }

    @Test
    void persistenceFailureNeverReturnsReadyAndStartsIndependentFailureCleanup() throws Exception {
        MockMultipartFile file = textFile();
        stubDocumentCreation(file);
        List<KnowledgeChunk> prepared = List.of(chunk(0));
        when(chunkService.prepareChunks(eq(10L), eq(20L), anyString())).thenReturn(prepared);
        when(persistenceService.persistPreparedChunks(10L, 20L, prepared))
                .thenThrow(new KnowledgePersistenceService.ProcessingStateException("ready rejected"));
        when(persistenceService.markFailed(10L, 20L, "Document persistence failed")).thenReturn(true);

        assertThrows(KnowledgeDocumentService.DocumentProcessingException.class,
                () -> service.uploadAndProcess(file, 20L));

        verify(persistenceService).markFailed(10L, 20L, "Document persistence failed");
    }

    @Test
    void ownerCanDeleteReadyAndFailedDocumentsWithChunkCleanup() {
        for (String status : List.of("READY", "FAILED")) {
            KnowledgeDocument document = document(status);
            when(documentMapper.selectOwnedById(10L, 20L)).thenReturn(document);
            when(chunkMapper.deleteByDocumentAndUser(10L, 20L)).thenReturn(2);
            when(chunkMapper.countByDocumentAndUser(10L, 20L)).thenReturn(0);
            when(documentMapper.deleteOwnedReadyOrFailed(10L, 20L)).thenReturn(1);

            service.deleteDocument(10L, 20L);

            verify(chunkMapper).deleteByDocumentAndUser(10L, 20L);
            verify(documentMapper).deleteOwnedReadyOrFailed(10L, 20L);
            org.mockito.Mockito.clearInvocations(chunkMapper, documentMapper);
        }
    }

    @Test
    void otherUsersDocumentCannotBeDeleted() {
        when(documentMapper.selectOwnedById(10L, 20L)).thenReturn(null);

        assertThrows(KnowledgeDocumentService.DocumentNotFoundException.class,
                () -> service.deleteDocument(10L, 20L));

        verify(chunkMapper, never()).deleteByDocumentAndUser(any(), any());
        verify(documentMapper, never()).deleteOwnedReadyOrFailed(any(), any());
    }

    @Test
    void processingDocumentDeletionIsRejected() {
        when(documentMapper.selectOwnedById(10L, 20L)).thenReturn(document("PROCESSING"));

        assertThrows(KnowledgeDocumentService.DocumentConflictException.class,
                () -> service.deleteDocument(10L, 20L));

        verify(chunkMapper, never()).deleteByDocumentAndUser(any(), any());
    }

    private void stubDocumentCreation(MockMultipartFile file) throws Exception {
        when(minioUtils.uploadFile(file)).thenReturn("http://localhost:9000/media/doc.txt");
        doAnswer(invocation -> {
            KnowledgeDocument document = invocation.getArgument(0);
            document.setId(10L);
            return 1;
        }).when(documentMapper).insert(any(KnowledgeDocument.class));
    }

    private MockMultipartFile textFile() {
        return new MockMultipartFile(
                "file", "doc.txt", "text/plain", "knowledge".getBytes(StandardCharsets.UTF_8));
    }

    private KnowledgeChunk chunk(int index) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setDocumentId(10L);
        chunk.setUserId(20L);
        chunk.setChunkIndex(index);
        return chunk;
    }

    private KnowledgeDocument document(String status) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(10L);
        document.setUserId(20L);
        document.setStatus(status);
        document.setChunkCount(0);
        return document;
    }
}
