-- REPEATABLE MIGRATION. Safe to execute again after a successful application.
-- Required server version: MySQL 8.0.16 or newer (CHECK constraints are enforced).
-- Stop application producers and RocketMQ consumers before applying this migration.

SELECT VERSION();

USE `media_db`;

DROP PROCEDURE IF EXISTS `migrate_add_analysis_result_metadata`;

DELIMITER $$

CREATE PROCEDURE `migrate_add_analysis_result_metadata`()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'media_files'
      AND COLUMN_NAME = 'result_request_id'
  ) THEN
    ALTER TABLE `media_files`
      ADD COLUMN `result_request_id` char(36) DEFAULT NULL
        COMMENT 'Request UUID that produced the current successful result'
        AFTER `analysis_finished_at`;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'media_files'
      AND COLUMN_NAME = 'result_mode'
  ) THEN
    ALTER TABLE `media_files`
      ADD COLUMN `result_mode` varchar(10) DEFAULT NULL
        COMMENT 'Mode that produced the current successful result'
        AFTER `result_request_id`;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'media_files'
      AND COLUMN_NAME = 'result_goal'
  ) THEN
    ALTER TABLE `media_files`
      ADD COLUMN `result_goal` varchar(500) DEFAULT NULL
        COMMENT 'Goal that produced the current successful result'
        AFTER `result_mode`;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'media_files'
      AND COLUMN_NAME = 'result_finished_at'
  ) THEN
    ALTER TABLE `media_files`
      ADD COLUMN `result_finished_at` datetime DEFAULT NULL
        COMMENT 'Current successful result completion time'
        AFTER `result_goal`;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'media_files'
      AND CONSTRAINT_NAME = 'chk_media_files_result_mode'
  ) THEN
    ALTER TABLE `media_files`
      ADD CONSTRAINT `chk_media_files_result_mode`
        CHECK (`result_mode` IS NULL OR `result_mode` IN ('FULL', 'GOAL'));
  END IF;
END$$

DELIMITER ;

CALL `migrate_add_analysis_result_metadata`();
DROP PROCEDURE `migrate_add_analysis_result_metadata`;

-- Backfill only rows whose successful result can be tied to a valid current request
-- and an unambiguous mode/goal. Rows from older schemas without a trustworthy
-- request UUID or GOAL text remain unattributed instead of receiving guessed metadata.
UPDATE `media_files`
SET `result_request_id` = `analysis_request_id`,
    `result_mode` = `analysis_mode`,
    `result_goal` = CASE
      WHEN `analysis_mode` = 'FULL'
      THEN 'Summarize the complete video in chronological order and generate a structured analysis report'
      ELSE TRIM(`analysis_goal`)
    END,
    `result_finished_at` = `analysis_finished_at`
WHERE `result_request_id` IS NULL
  AND `result_mode` IS NULL
  AND `result_goal` IS NULL
  AND `result_finished_at` IS NULL
  AND `analysis_status` = 'SUCCESS'
  AND `ai_summary` IS NOT NULL
  AND TRIM(`ai_summary`) <> ''
  AND `analysis_request_id` REGEXP
      '^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$'
  AND `analysis_mode` IN ('FULL', 'GOAL')
  AND (`analysis_mode` = 'FULL'
       OR (`analysis_goal` IS NOT NULL AND TRIM(`analysis_goal`) <> ''));
