package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.KnowledgeDocument;
import com.example.server.mapper.KnowledgeDocumentMapper;
import com.example.server.utils.MinioUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class KnowledgeDocumentService {

    @Autowired
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Autowired
    private KnowledgeChunkService knowledgeChunkService;

    @Autowired
    private MinioUtils minioUtils;

    @Value("${minio.bucketName}")
    private String minioBucket;

    public KnowledgeDocument uploadAndProcess(MultipartFile file, Long userId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is empty");
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
            throw new IllegalStateException("MinIO upload failed: " + e.getMessage(), e);
        }

        document.setMinioObjectKey(getObjectKey(fileUrl));
        knowledgeDocumentMapper.insert(document);

        try {
            String content = readText(file);
            int chunkCount = knowledgeChunkService.splitAndSave(document.getId(), userId, content);

            document.setStatus("READY");
            document.setChunkCount(chunkCount);
            document.setErrorMessage(null);
            document.setUpdateTime(LocalDateTime.now());
            knowledgeDocumentMapper.updateById(document);
        } catch (Exception e) {
            document.setStatus("FAILED");
            document.setErrorMessage(truncateError(e.getMessage()));
            document.setUpdateTime(LocalDateTime.now());
            if (document.getId() != null) {
                knowledgeDocumentMapper.updateById(document);
            }
        }

        return document;
    }

    public List<KnowledgeDocument> listByUser(Long userId) {
        QueryWrapper<KnowledgeDocument> query = new QueryWrapper<>();
        query.eq("user_id", userId);
        query.orderByDesc("id");
        return knowledgeDocumentMapper.selectList(query);
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

    private String truncateError(String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        return message.length() > 2048 ? message.substring(0, 2048) : message;
    }
}
