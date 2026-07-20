CREATE TABLE IF NOT EXISTS knowledge_documents (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '知识库文档ID',
  user_id BIGINT NOT NULL COMMENT '上传用户ID',
  original_filename VARCHAR(512) NOT NULL COMMENT '原始文件名',
  file_ext VARCHAR(20) NOT NULL COMMENT '文件扩展名',
  file_size BIGINT DEFAULT NULL COMMENT '文件大小',
  minio_bucket VARCHAR(100) NOT NULL COMMENT 'MinIO桶名',
  minio_object_key VARCHAR(1024) NOT NULL COMMENT 'MinIO对象Key',
  status VARCHAR(50) NOT NULL COMMENT '处理状态：PROCESSING/READY/FAILED',
  chunk_count INT DEFAULT 0 COMMENT '切块数量',
  error_message VARCHAR(2048) DEFAULT NULL COMMENT '失败原因',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_knowledge_documents_id_user_id (id, user_id),
  KEY idx_knowledge_documents_user_id (user_id),
  KEY idx_knowledge_documents_status (status),
  KEY idx_knowledge_documents_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档表';

CREATE TABLE IF NOT EXISTS knowledge_chunks (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '文档切块ID',
  document_id BIGINT NOT NULL COMMENT '文档ID',
  user_id BIGINT NOT NULL COMMENT '用户ID',
  chunk_index INT NOT NULL COMMENT '切块序号',
  content LONGTEXT NOT NULL COMMENT '切块文本内容',
  content_hash VARCHAR(64) DEFAULT NULL COMMENT '内容哈希',
  char_count INT DEFAULT NULL COMMENT '字符数',
  embedding LONGTEXT NULL COMMENT '向量JSON字符串',
  embedding_model VARCHAR(100) DEFAULT NULL COMMENT 'Embedding模型',
  embedding_dim INT DEFAULT NULL COMMENT '向量维度',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_knowledge_chunks_document_chunk_index (document_id, chunk_index),
  KEY idx_knowledge_chunks_document_user (document_id, user_id),
  KEY idx_knowledge_chunks_document_id (document_id),
  KEY idx_knowledge_chunks_user_id (user_id),
  KEY idx_knowledge_chunks_content_hash (content_hash),
  CONSTRAINT fk_knowledge_chunks_document_user
    FOREIGN KEY (document_id, user_id)
    REFERENCES knowledge_documents (id, user_id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库切块表';
