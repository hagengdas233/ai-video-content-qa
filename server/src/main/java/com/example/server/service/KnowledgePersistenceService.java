package com.example.server.service;

import com.example.server.entity.KnowledgeChunk;
import com.example.server.entity.KnowledgeDocument;
import com.example.server.mapper.KnowledgeChunkMapper;
import com.example.server.mapper.KnowledgeDocumentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class KnowledgePersistenceService {

    private static final int MAX_ERROR_SUMMARY_LENGTH = 512;
    public static final String ERROR_TEXT_EXTRACTION = "Document text extraction failed";
    public static final String ERROR_CHUNK_PREPARATION = "Document chunk preparation failed";
    public static final String ERROR_PERSISTENCE = "Document persistence failed";
    private static final String ERROR_GENERIC = "Document processing failed";

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;

    public KnowledgePersistenceService(KnowledgeDocumentMapper knowledgeDocumentMapper,
                                       KnowledgeChunkMapper knowledgeChunkMapper) {
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
    }

    @Transactional
    public int persistPreparedChunks(Long documentId, Long userId, List<KnowledgeChunk> chunks) {
        requireIdentifiers(documentId, userId);
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("prepared chunks must not be empty");
        }
        validatePreparedChunks(documentId, userId, chunks);

        KnowledgeDocument document = knowledgeDocumentMapper.selectOwnedById(documentId, userId);
        if (document == null) {
            throw new ProcessingStateException("Knowledge document no longer exists");
        }
        if (!"PROCESSING".equals(document.getStatus())) {
            throw new ProcessingStateException("Knowledge document is no longer PROCESSING");
        }

        int deleted = knowledgeChunkMapper.deleteByDocumentAndUser(documentId, userId);
        requireValidDeleteCount(deleted);
        requireCleanupComplete(documentId, userId);

        int inserted = knowledgeChunkMapper.insertBatch(chunks);
        if (inserted != chunks.size()) {
            throw new IllegalStateException("Knowledge chunk batch insert was incomplete");
        }

        int updated = knowledgeDocumentMapper.markReadyIfProcessing(
                documentId, userId, inserted, LocalDateTime.now());
        if (updated != 1) {
            throw new ProcessingStateException("Knowledge document READY transition was rejected");
        }
        return inserted;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(Long documentId, Long userId, String errorSummary) {
        requireIdentifiers(documentId, userId);

        int deleted = knowledgeChunkMapper.deleteByDocumentAndUser(documentId, userId);
        requireValidDeleteCount(deleted);
        requireCleanupComplete(documentId, userId);

        int updated = knowledgeDocumentMapper.markFailedIfProcessing(
                documentId, userId, safeSummary(errorSummary), LocalDateTime.now());
        if (updated == 1) {
            return true;
        }

        KnowledgeDocument document = knowledgeDocumentMapper.selectOwnedById(documentId, userId);
        if (document == null) {
            return false;
        }
        if ("FAILED".equals(document.getStatus()) && Integer.valueOf(0).equals(document.getChunkCount())) {
            return true;
        }
        throw new ProcessingStateException("Knowledge document FAILED transition was rejected");
    }

    private void requireIdentifiers(Long documentId, Long userId) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId is required");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
    }

    private void validatePreparedChunks(Long documentId, Long userId, List<KnowledgeChunk> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = chunks.get(i);
            if (chunk == null
                    || !documentId.equals(chunk.getDocumentId())
                    || !userId.equals(chunk.getUserId())
                    || !Integer.valueOf(i).equals(chunk.getChunkIndex())) {
                throw new IllegalArgumentException("Prepared chunk ownership or index is invalid");
            }
        }
    }

    private void requireValidDeleteCount(int deleted) {
        if (deleted < 0) {
            throw new IllegalStateException("Knowledge chunk cleanup returned an invalid count");
        }
    }

    private void requireCleanupComplete(Long documentId, Long userId) {
        int remaining = knowledgeChunkMapper.countByDocumentAndUser(documentId, userId);
        if (remaining != 0) {
            throw new IllegalStateException("Knowledge chunk cleanup was incomplete");
        }
    }

    private String safeSummary(String errorSummary) {
        String summary = ERROR_TEXT_EXTRACTION.equals(errorSummary)
                || ERROR_CHUNK_PREPARATION.equals(errorSummary)
                || ERROR_PERSISTENCE.equals(errorSummary)
                ? errorSummary
                : ERROR_GENERIC;
        return summary.length() > MAX_ERROR_SUMMARY_LENGTH
                ? summary.substring(0, MAX_ERROR_SUMMARY_LENGTH)
                : summary;
    }

    public static class ProcessingStateException extends RuntimeException {
        public ProcessingStateException(String message) {
            super(message);
        }
    }
}
