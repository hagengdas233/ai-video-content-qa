USE `media_db`;

-- Run once for an existing database. The column remains nullable for legacy rows.
ALTER TABLE `media_files`
  ADD COLUMN `content_hash` char(32) DEFAULT NULL
  COMMENT 'Server-computed lowercase MD5 of final video content'
  AFTER `file_path`;
