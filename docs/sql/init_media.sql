CREATE DATABASE IF NOT EXISTS `media_db`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE `media_db`;

CREATE TABLE IF NOT EXISTS `users` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'User ID',
  `username` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Username',
  `password` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Password hash',
  `nickname` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Nickname',
  `avatar` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Avatar URL',
  `role` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT 'USER' COMMENT 'User role',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_users_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Users';

CREATE TABLE IF NOT EXISTS `media_files` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Media file ID',
  `user_id` bigint DEFAULT NULL COMMENT 'Uploader user ID',
  `filename` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Original filename',
  `status` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Processing status',
  `file_path` varchar(2048) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'File storage path or MinIO URL',
  `content_hash` char(32) DEFAULT NULL COMMENT 'Server-computed lowercase MD5 of final video content',
  `ai_summary` longtext COLLATE utf8mb4_unicode_ci COMMENT 'AI summary in Markdown',
  `transcript_text` longtext COLLATE utf8mb4_unicode_ci COMMENT 'Transcript text',
  `cover_url` varchar(2048) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Cover URL',
  `upload_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT 'Upload time',
  PRIMARY KEY (`id`),
  KEY `idx_media_files_user_id` (`user_id`),
  KEY `idx_media_files_upload_time` (`upload_time`),
  KEY `idx_media_files_status` (`status`),
  CONSTRAINT `fk_media_files_user_id`
    FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
    ON DELETE SET NULL
    ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Media files';
