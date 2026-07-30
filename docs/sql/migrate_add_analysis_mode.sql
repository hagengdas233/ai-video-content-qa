-- REPEATABLE MIGRATION. Safe to execute again after a successful application.
-- Required server version: MySQL 8.0.16 or newer (CHECK constraints are enforced).
-- Stop application producers and RocketMQ consumers before applying this migration.

SELECT VERSION();

USE `media_db`;

-- Existing analysis results predate explicit modes and are treated as FULL.
-- Existing analysis_goal values are preserved for auditability.
DROP PROCEDURE IF EXISTS `migrate_add_analysis_mode`;

DELIMITER $$

CREATE PROCEDURE `migrate_add_analysis_mode`()
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'media_files'
      AND COLUMN_NAME = 'analysis_mode'
  ) THEN
    ALTER TABLE `media_files`
      ADD COLUMN `analysis_mode` varchar(10) NOT NULL DEFAULT 'FULL'
        COMMENT 'FULL or GOAL' AFTER `analysis_request_id`;
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'media_files'
      AND CONSTRAINT_NAME = 'chk_media_files_analysis_mode'
  ) THEN
    ALTER TABLE `media_files`
      ADD CONSTRAINT `chk_media_files_analysis_mode`
        CHECK (`analysis_mode` IN ('FULL', 'GOAL'));
  END IF;
END$$

DELIMITER ;

CALL `migrate_add_analysis_mode`();
DROP PROCEDURE `migrate_add_analysis_mode`;
