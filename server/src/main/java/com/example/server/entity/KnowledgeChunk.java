package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_chunks")
public class KnowledgeChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private Long userId;

    private Integer chunkIndex;

    private String content;

    private String contentHash;

    private Integer charCount;

    private String embedding;

    private String embeddingModel;

    private Integer embeddingDim;

    private LocalDateTime createTime;
}
