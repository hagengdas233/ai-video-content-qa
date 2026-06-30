package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_documents")
public class KnowledgeDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String originalFilename;

    private String fileExt;

    private Long fileSize;

    private String minioBucket;

    private String minioObjectKey;

    private String status;

    private Integer chunkCount;

    private String errorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
