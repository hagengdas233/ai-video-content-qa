package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.KnowledgeDocument;
import com.example.server.mapper.KnowledgeChunkMapper;
import com.example.server.mapper.KnowledgeDocumentMapper;
import com.example.server.utils.MinioUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class KnowledgeDocumentService {

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeChunkService knowledgeChunkService;
    private final KnowledgePersistenceService knowledgePersistenceService;
    private final MinioUtils minioUtils;
    private final String minioBucket;

    public KnowledgeDocumentService(KnowledgeDocumentMapper knowledgeDocumentMapper,
                                    KnowledgeChunkMapper knowledgeChunkMapper,
                                    KnowledgeChunkService knowledgeChunkService,
                                    KnowledgePersistenceService knowledgePersistenceService,
                                    MinioUtils minioUtils,
                                    @Value("${minio.bucketName}") String minioBucket) {
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.knowledgeChunkService = knowledgeChunkService;
        this.knowledgePersistenceService = knowledgePersistenceService;
        this.minioUtils = minioUtils;
        this.minioBucket = minioBucket;
    }

    public KnowledgeDocument uploadAndProcess(MultipartFile file, Long userId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is empty");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }

        String originalFilename = file.getOriginalFilename();
        String fileExt = getFileExt(originalFilename);
        if (!".txt".equals(fileExt) && !".md".equals(fileExt)) {
            throw new IllegalArgumentException("only .txt and .md files are supported");
        }

        KnowledgeDocument document = new KnowledgeDocument();
        document.setUserId(userId);
        document.setOriginalFilename(originalFilename);
        document.setFileExt(fileExt.substring(1));
        document.setFileSize(file.getSize());
        document.setMinioBucket(minioBucket);
        document.setStatus("PROCESSING");
        document.setChunkCount(0);
        document.setCreateTime(LocalDateTime.now());
        document.setUpdateTime(LocalDateTime.now());

        String fileUrl;
        try {
            fileUrl = minioUtils.uploadFile(file);
        } catch (Exception e) {
            throw new IllegalStateException("MinIO upload failed", e);
        }

        document.setMinioObjectKey(getObjectKey(fileUrl));
        int documentInserted = knowledgeDocumentMapper.insert(document);
        if (documentInserted != 1 || document.getId() == null) {
            throw new IllegalStateException("Knowledge document creation failed");
        }

        String failureSummary = KnowledgePersistenceService.ERROR_TEXT_EXTRACTION;
        try {
            String content = readText(file);
            if (content.isBlank()) {
                throw new IllegalArgumentException("document contains no valid text");
            }

            failureSummary = KnowledgePersistenceService.ERROR_CHUNK_PREPARATION;
            var chunks = knowledgeChunkService.prepareChunks(document.getId(), userId, content);

            failureSummary = KnowledgePersistenceService.ERROR_PERSISTENCE;
            int chunkCount = knowledgePersistenceService.persistPreparedChunks(
                    document.getId(), userId, chunks);
            document.setStatus("READY");
            document.setChunkCount(chunkCount);
            document.setErrorMessage(null);
            document.setUpdateTime(LocalDateTime.now());
        } catch (Exception e) {
            try {
                boolean failureRecorded = knowledgePersistenceService.markFailed(
                        document.getId(), userId, failureSummary);
                if (!failureRecorded) {
                    e.addSuppressed(new KnowledgePersistenceService.ProcessingStateException(
                            "Knowledge document was deleted before failure status could be recorded"));
                }
            } catch (Exception failureStateException) {
                e.addSuppressed(failureStateException);
            }
            throw new DocumentProcessingException("Document processing failed", e);
        }

        return document;
    }

    public List<KnowledgeDocument> listByUser(Long userId) {
        QueryWrapper<KnowledgeDocument> query = new QueryWrapper<>();
        query.eq("user_id", userId);
        query.orderByDesc("id");
        return knowledgeDocumentMapper.selectList(query);
    }

    @Transactional
    public void deleteDocument(Long documentId, Long userId) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId is required");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }

        KnowledgeDocument document = knowledgeDocumentMapper.selectOwnedById(documentId, userId);
        if (document == null) {
            throw new DocumentNotFoundException("Knowledge document not found");
        }
        if ("PROCESSING".equals(document.getStatus())) {
            throw new DocumentConflictException("PROCESSING document cannot be deleted");
        }
        if (!"READY".equals(document.getStatus()) && !"FAILED".equals(document.getStatus())) {
            throw new DocumentConflictException("Document status does not allow deletion");
        }

        int deletedChunks = knowledgeChunkMapper.deleteByDocumentAndUser(documentId, userId);
        if (deletedChunks < 0) {
            throw new IllegalStateException("Knowledge chunk cleanup returned an invalid count");
        }
        if (knowledgeChunkMapper.countByDocumentAndUser(documentId, userId) != 0) {
            throw new IllegalStateException("Knowledge chunk cleanup was incomplete");
        }

        int deleted = knowledgeDocumentMapper.deleteOwnedReadyOrFailed(documentId, userId);
        if (deleted != 1) {
            throw new DocumentConflictException("Knowledge document deletion was rejected");
        }

        if (document.getMinioObjectKey() != null && !document.getMinioObjectKey().isBlank()) {
            minioUtils.removeFile(document.getMinioObjectKey());
        }
    }

    private String readText(MultipartFile file) throws Exception {
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        if (content.startsWith("\uFEFF")) {
            return content.substring(1);
        }
        return content;
    }

    private String getFileExt(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0) {
            return "";
        }
        return filename.substring(dotIndex).toLowerCase();
    }

    private String getObjectKey(String fileUrl) {
        int slashIndex = fileUrl.lastIndexOf('/');
        if (slashIndex < 0 || slashIndex == fileUrl.length() - 1) {
            return fileUrl;
        }
        return fileUrl.substring(slashIndex + 1);
    }

    public static class DocumentNotFoundException extends RuntimeException {
        public DocumentNotFoundException(String message) {
            super(message);
        }
    }

    public static class DocumentConflictException extends RuntimeException {
        public DocumentConflictException(String message) {
            super(message);
        }
    }

    public static class DocumentProcessingException extends RuntimeException {
        public DocumentProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
