-- NON-REPEATABLE MIGRATION. Apply exactly once and record its execution externally.
-- Required server version: MySQL 8.0.16 or newer (CHECK constraints are enforced from 8.0.16).
-- Stop application producers and RocketMQ consumers before executing this file.
-- Do not continue until the following query confirms a supported server version.
SELECT VERSION();

USE `media_db`;

-- Create a recoverable backup before running the ALTER/UPDATE statements below.
-- Replace the timestamp suffix with the actual execution time, then run both statements:
-- CREATE TABLE `media_files_before_analysis_state_YYYYMMDDHHMMSS` LIKE `media_files`;
-- INSERT INTO `media_files_before_analysis_state_YYYYMMDDHHMMSS`
-- SELECT * FROM `media_files`;
-- Verify the source and backup row counts are identical before continuing.

ALTER TABLE `media_files`
  ADD COLUMN `analysis_status` varchar(20) NOT NULL DEFAULT 'NOT_STARTED'
    COMMENT 'NOT_STARTED, QUEUED, RUNNING, SUCCESS, FAILED' AFTER `transcript_text`,
  ADD COLUMN `analysis_request_id` char(36) DEFAULT NULL
    COMMENT 'Current analysis request UUID' AFTER `analysis_status`,
  ADD COLUMN `analysis_goal` varchar(500) DEFAULT NULL
    COMMENT 'Current analysis goal' AFTER `analysis_request_id`,
  ADD COLUMN `analysis_error` varchar(1000) DEFAULT NULL
    COMMENT 'Most recent analysis error' AFTER `analysis_goal`,
  ADD COLUMN `analysis_started_at` datetime DEFAULT NULL
    COMMENT 'Current analysis start time' AFTER `analysis_error`,
  ADD COLUMN `analysis_finished_at` datetime DEFAULT NULL
    COMMENT 'Current analysis finish time' AFTER `analysis_started_at`,
  ADD KEY `idx_media_files_analysis_status` (`analysis_status`),
  ADD UNIQUE KEY `uk_media_files_analysis_request_id` (`analysis_request_id`),
  ADD CONSTRAINT `chk_media_files_analysis_status`
    CHECK (`analysis_status` IN ('NOT_STARTED', 'QUEUED', 'RUNNING', 'SUCCESS', 'FAILED'));

-- Git history shows only these ai_summary placeholders were persisted:
--   [MQ] Analysis task queued
--   [MQ] 已进入消息队列，等待调度...
--   ❌ 分析失败: <error>
-- Every other non-blank summary is treated as a real result and is preserved.
UPDATE `media_files`
SET `analysis_status` = CASE
      WHEN `ai_summary` IS NULL OR TRIM(`ai_summary`) = '' THEN 'NOT_STARTED'
      WHEN BINARY TRIM(`ai_summary`) = BINARY '[MQ] Analysis task queued' THEN 'NOT_STARTED'
      WHEN BINARY TRIM(`ai_summary`) = BINARY '[MQ] 已进入消息队列，等待调度...' THEN 'NOT_STARTED'
      WHEN LEFT(TRIM(`ai_summary`), CHAR_LENGTH('❌ 分析失败:')) = '❌ 分析失败:' THEN 'FAILED'
      ELSE 'SUCCESS'
    END,
    `analysis_error` = CASE
      WHEN LEFT(TRIM(`ai_summary`), CHAR_LENGTH('❌ 分析失败:')) = '❌ 分析失败:'
      THEN LEFT(NULLIF(TRIM(SUBSTRING(
          TRIM(`ai_summary`), CHAR_LENGTH('❌ 分析失败:') + 1)), ''), 1000)
      ELSE NULL
    END,
    -- Historical completion time is unknown; upload_time must not impersonate it.
    `analysis_finished_at` = NULL;

-- Clear ai_summary only for the exact legacy formats identified above.
UPDATE `media_files`
SET `ai_summary` = NULL
WHERE BINARY TRIM(`ai_summary`) = BINARY '[MQ] Analysis task queued'
   OR BINARY TRIM(`ai_summary`) = BINARY '[MQ] 已进入消息队列，等待调度...'
   OR LEFT(TRIM(`ai_summary`), CHAR_LENGTH('❌ 分析失败:')) = '❌ 分析失败:';
