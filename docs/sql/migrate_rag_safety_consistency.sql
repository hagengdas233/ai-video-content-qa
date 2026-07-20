-- RAG tenant-isolation and consistency migration for MySQL 8.0.46.
-- DO NOT execute without a verified backup and a reviewed precheck_rag_safety_consistency.sql run.
-- MySQL DDL implicitly commits. This script cannot provide whole-script transaction rollback.
-- Do not use the mysql client's --force option: SIGNAL must stop execution immediately.
--
-- Recovery after any failure:
--   1. Stop. Do not issue ad-hoc DROP statements.
--   2. Run the read-only precheck again and compare the result with the backup.
--   3. Correctly defined partial steps are detected by their complete structure, even under a
--      different name, and are skipped when this script is rerun.
--   4. A target name with a different definition is an explicit blocker. Back up, identify its
--      owner and purpose, and repair it deliberately; never DROP an unknown index or foreign key.
--   5. If an unexpected DDL failure occurs after lifecycle cleanup, rerun the precheck before
--      continuing. Legal READY chunks are never part of the automatic cleanup.

DELIMITER $$

DROP PROCEDURE IF EXISTS rag_safety_guard$$
CREATE PROCEDURE rag_safety_guard(IN p_require_no_lifecycle_residual BOOLEAN)
BEGIN
    DECLARE v_blockers BIGINT DEFAULT 0;
    DECLARE v_count BIGINT DEFAULT 0;
    DECLARE v_message VARCHAR(255);

    SELECT COUNT(*) INTO v_count
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME IN ('knowledge_documents', 'knowledge_chunks')
      AND ENGINE = 'InnoDB';
    IF v_count <> 2 THEN
        SET v_blockers = v_blockers + 1;
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND (
           (TABLE_NAME = 'knowledge_documents' AND COLUMN_NAME = 'id'
            AND COLUMN_TYPE = 'bigint' AND IS_NULLABLE = 'NO')
        OR (TABLE_NAME = 'knowledge_documents' AND COLUMN_NAME = 'user_id'
            AND COLUMN_TYPE = 'bigint' AND IS_NULLABLE = 'NO')
        OR (TABLE_NAME = 'knowledge_chunks' AND COLUMN_NAME = 'document_id'
            AND COLUMN_TYPE = 'bigint' AND IS_NULLABLE = 'NO')
        OR (TABLE_NAME = 'knowledge_chunks' AND COLUMN_NAME = 'user_id'
            AND COLUMN_TYPE = 'bigint' AND IS_NULLABLE = 'NO')
        OR (TABLE_NAME = 'knowledge_chunks' AND COLUMN_NAME = 'chunk_index'
            AND COLUMN_TYPE = 'int' AND IS_NULLABLE = 'NO')
      );
    IF v_count <> 5 THEN
        SET v_blockers = v_blockers + 1;
    END IF;

    IF v_blockers > 0 THEN
        SET v_message = CONCAT('RAG safety migration blocked by table engine or column definition; count=',
                               v_blockers, '. Run the read-only precheck.');
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_message;
    END IF;

    SELECT COUNT(*) INTO v_count
    FROM knowledge_chunks c
    LEFT JOIN knowledge_documents d ON d.id = c.document_id
    WHERE d.id IS NULL;
    SET v_blockers = v_blockers + v_count;

    SELECT COUNT(*) INTO v_count
    FROM knowledge_chunks c
    JOIN knowledge_documents d ON d.id = c.document_id
    WHERE c.user_id <> d.user_id;
    SET v_blockers = v_blockers + v_count;

    SELECT COUNT(*) INTO v_count
    FROM (
        SELECT c.document_id, c.chunk_index
        FROM knowledge_chunks c
        JOIN knowledge_documents d
          ON d.id = c.document_id
         AND d.user_id = c.user_id
        WHERE d.status NOT IN ('FAILED', 'PROCESSING')
        GROUP BY c.document_id, c.chunk_index
        HAVING COUNT(*) > 1
    ) unsafe_duplicates;
    SET v_blockers = v_blockers + v_count;

    IF p_require_no_lifecycle_residual THEN
        SELECT COUNT(*) INTO v_count
        FROM knowledge_chunks c
        JOIN knowledge_documents d
          ON d.id = c.document_id
         AND d.user_id = c.user_id
        WHERE d.status IN ('FAILED', 'PROCESSING');
        SET v_blockers = v_blockers + v_count;

        SELECT COUNT(*) INTO v_count
        FROM (
            SELECT document_id, chunk_index
            FROM knowledge_chunks
            GROUP BY document_id, chunk_index
            HAVING COUNT(*) > 1
        ) remaining_duplicates;
        SET v_blockers = v_blockers + v_count;
    END IF;

    IF v_blockers > 0 THEN
        SET v_message = CONCAT('RAG safety migration blocked by data; blocking count=', v_blockers,
                               '. Run and review the read-only precheck.');
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_message;
    END IF;
END$$

DROP PROCEDURE IF EXISTS rag_safety_plan_constraints$$
CREATE PROCEDURE rag_safety_plan_constraints()
BEGIN
    DECLARE v_blockers BIGINT DEFAULT 0;
    DECLARE v_named_exists BIGINT DEFAULT 0;
    DECLARE v_named_matches BIGINT DEFAULT 0;
    DECLARE v_structural_matches BIGINT DEFAULT 0;
    DECLARE v_wrong_uniqueness BIGINT DEFAULT 0;
    DECLARE v_wrong_foreign_keys BIGINT DEFAULT 0;
    DECLARE v_message VARCHAR(255);

    SET @rag_metadata_validated = 0;
    SET @rag_need_parent_unique = NULL;
    SET @rag_need_chunk_unique = NULL;
    SET @rag_need_chunk_owner_index = NULL;
    SET @rag_need_owner_foreign_key = NULL;

    -- Parent UNIQUE(id,user_id): exact two non-prefix, non-expression BTREE columns in order.
    SELECT COUNT(*) INTO v_structural_matches
    FROM (
        SELECT INDEX_NAME
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'knowledge_documents'
        GROUP BY INDEX_NAME
        HAVING MIN(NON_UNIQUE) = 0
           AND COUNT(*) = 2
           AND COUNT(DISTINCT SEQ_IN_INDEX) = 2
           AND MIN(SEQ_IN_INDEX) = 1
           AND MAX(SEQ_IN_INDEX) = 2
           AND GROUP_CONCAT(COALESCE(COLUMN_NAME, '<expression>')
                            ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id,user_id'
           AND SUM(CASE WHEN SUB_PART IS NOT NULL THEN 1 ELSE 0 END) = 0
           AND SUM(CASE WHEN COLUMN_NAME IS NULL OR EXPRESSION IS NOT NULL THEN 1 ELSE 0 END) = 0
           AND MIN(INDEX_TYPE) = 'BTREE'
           AND MAX(INDEX_TYPE) = 'BTREE'
    ) exact_parent_unique;
    SELECT COUNT(*) INTO v_named_exists
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'knowledge_documents'
      AND INDEX_NAME = 'uk_knowledge_documents_id_user_id';
    SELECT COUNT(*) INTO v_named_matches
    FROM (
        SELECT INDEX_NAME
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'knowledge_documents'
          AND INDEX_NAME = 'uk_knowledge_documents_id_user_id'
        GROUP BY INDEX_NAME
        HAVING MIN(NON_UNIQUE) = 0
           AND COUNT(*) = 2
           AND COUNT(DISTINCT SEQ_IN_INDEX) = 2
           AND MIN(SEQ_IN_INDEX) = 1
           AND MAX(SEQ_IN_INDEX) = 2
           AND GROUP_CONCAT(COALESCE(COLUMN_NAME, '<expression>')
                            ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id,user_id'
           AND SUM(CASE WHEN SUB_PART IS NOT NULL THEN 1 ELSE 0 END) = 0
           AND SUM(CASE WHEN COLUMN_NAME IS NULL OR EXPRESSION IS NOT NULL THEN 1 ELSE 0 END) = 0
           AND MIN(INDEX_TYPE) = 'BTREE'
           AND MAX(INDEX_TYPE) = 'BTREE'
    ) exact_named_parent_unique;
    IF v_named_exists > 0 AND v_named_matches <> 1 THEN
        SET v_blockers = v_blockers + 1;
    END IF;
    SELECT COUNT(*) INTO v_wrong_uniqueness
    FROM (
        SELECT INDEX_NAME
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'knowledge_documents'
        GROUP BY INDEX_NAME
        HAVING MIN(NON_UNIQUE) <> 0
           AND COUNT(*) = 2
           AND GROUP_CONCAT(COALESCE(COLUMN_NAME, '<expression>')
                            ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'id,user_id'
           AND SUM(CASE WHEN SUB_PART IS NOT NULL THEN 1 ELSE 0 END) = 0
           AND SUM(CASE WHEN COLUMN_NAME IS NULL OR EXPRESSION IS NOT NULL THEN 1 ELSE 0 END) = 0
    ) non_unique_parent_candidate;
    SET v_blockers = v_blockers + v_wrong_uniqueness;
    SET @rag_need_parent_unique = IF(v_structural_matches = 0, 1, 0);

    -- Child UNIQUE(document_id,chunk_index): exact two non-prefix, non-expression BTREE columns.
    SELECT COUNT(*) INTO v_structural_matches
    FROM (
        SELECT INDEX_NAME
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'knowledge_chunks'
        GROUP BY INDEX_NAME
        HAVING MIN(NON_UNIQUE) = 0
           AND COUNT(*) = 2
           AND COUNT(DISTINCT SEQ_IN_INDEX) = 2
           AND MIN(SEQ_IN_INDEX) = 1
           AND MAX(SEQ_IN_INDEX) = 2
           AND GROUP_CONCAT(COALESCE(COLUMN_NAME, '<expression>')
                            ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'document_id,chunk_index'
           AND SUM(CASE WHEN SUB_PART IS NOT NULL THEN 1 ELSE 0 END) = 0
           AND SUM(CASE WHEN COLUMN_NAME IS NULL OR EXPRESSION IS NOT NULL THEN 1 ELSE 0 END) = 0
           AND MIN(INDEX_TYPE) = 'BTREE'
           AND MAX(INDEX_TYPE) = 'BTREE'
    ) exact_chunk_unique;
    SELECT COUNT(*) INTO v_named_exists
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'knowledge_chunks'
      AND INDEX_NAME = 'uk_knowledge_chunks_document_chunk_index';
    SELECT COUNT(*) INTO v_named_matches
    FROM (
        SELECT INDEX_NAME
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'knowledge_chunks'
          AND INDEX_NAME = 'uk_knowledge_chunks_document_chunk_index'
        GROUP BY INDEX_NAME
        HAVING MIN(NON_UNIQUE) = 0
           AND COUNT(*) = 2
           AND COUNT(DISTINCT SEQ_IN_INDEX) = 2
           AND MIN(SEQ_IN_INDEX) = 1
           AND MAX(SEQ_IN_INDEX) = 2
           AND GROUP_CONCAT(COALESCE(COLUMN_NAME, '<expression>')
                            ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'document_id,chunk_index'
           AND SUM(CASE WHEN SUB_PART IS NOT NULL THEN 1 ELSE 0 END) = 0
           AND SUM(CASE WHEN COLUMN_NAME IS NULL OR EXPRESSION IS NOT NULL THEN 1 ELSE 0 END) = 0
           AND MIN(INDEX_TYPE) = 'BTREE'
           AND MAX(INDEX_TYPE) = 'BTREE'
    ) exact_named_chunk_unique;
    IF v_named_exists > 0 AND v_named_matches <> 1 THEN
        SET v_blockers = v_blockers + 1;
    END IF;
    SELECT COUNT(*) INTO v_wrong_uniqueness
    FROM (
        SELECT INDEX_NAME
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'knowledge_chunks'
        GROUP BY INDEX_NAME
        HAVING MIN(NON_UNIQUE) <> 0
           AND COUNT(*) = 2
           AND GROUP_CONCAT(COALESCE(COLUMN_NAME, '<expression>')
                            ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'document_id,chunk_index'
           AND SUM(CASE WHEN SUB_PART IS NOT NULL THEN 1 ELSE 0 END) = 0
           AND SUM(CASE WHEN COLUMN_NAME IS NULL OR EXPRESSION IS NOT NULL THEN 1 ELSE 0 END) = 0
    ) non_unique_chunk_candidate;
    SET v_blockers = v_blockers + v_wrong_uniqueness;
    SET @rag_need_chunk_unique = IF(v_structural_matches = 0, 1, 0);

    -- Child ordinary INDEX(document_id,user_id): exact two-column order and ordinary (non-unique).
    SELECT COUNT(*) INTO v_structural_matches
    FROM (
        SELECT INDEX_NAME
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'knowledge_chunks'
        GROUP BY INDEX_NAME
        HAVING MIN(NON_UNIQUE) = 1
           AND MAX(NON_UNIQUE) = 1
           AND COUNT(*) = 2
           AND COUNT(DISTINCT SEQ_IN_INDEX) = 2
           AND MIN(SEQ_IN_INDEX) = 1
           AND MAX(SEQ_IN_INDEX) = 2
           AND GROUP_CONCAT(COALESCE(COLUMN_NAME, '<expression>')
                            ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'document_id,user_id'
           AND SUM(CASE WHEN SUB_PART IS NOT NULL THEN 1 ELSE 0 END) = 0
           AND SUM(CASE WHEN COLUMN_NAME IS NULL OR EXPRESSION IS NOT NULL THEN 1 ELSE 0 END) = 0
           AND MIN(INDEX_TYPE) = 'BTREE'
           AND MAX(INDEX_TYPE) = 'BTREE'
    ) exact_chunk_owner_index;
    SELECT COUNT(*) INTO v_named_exists
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'knowledge_chunks'
      AND INDEX_NAME = 'idx_knowledge_chunks_document_user';
    SELECT COUNT(*) INTO v_named_matches
    FROM (
        SELECT INDEX_NAME
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'knowledge_chunks'
          AND INDEX_NAME = 'idx_knowledge_chunks_document_user'
        GROUP BY INDEX_NAME
        HAVING MIN(NON_UNIQUE) = 1
           AND MAX(NON_UNIQUE) = 1
           AND COUNT(*) = 2
           AND COUNT(DISTINCT SEQ_IN_INDEX) = 2
           AND MIN(SEQ_IN_INDEX) = 1
           AND MAX(SEQ_IN_INDEX) = 2
           AND GROUP_CONCAT(COALESCE(COLUMN_NAME, '<expression>')
                            ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'document_id,user_id'
           AND SUM(CASE WHEN SUB_PART IS NOT NULL THEN 1 ELSE 0 END) = 0
           AND SUM(CASE WHEN COLUMN_NAME IS NULL OR EXPRESSION IS NOT NULL THEN 1 ELSE 0 END) = 0
           AND MIN(INDEX_TYPE) = 'BTREE'
           AND MAX(INDEX_TYPE) = 'BTREE'
    ) exact_named_chunk_owner_index;
    IF v_named_exists > 0 AND v_named_matches <> 1 THEN
        SET v_blockers = v_blockers + 1;
    END IF;
    SELECT COUNT(*) INTO v_wrong_uniqueness
    FROM (
        SELECT INDEX_NAME
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'knowledge_chunks'
        GROUP BY INDEX_NAME
        HAVING MIN(NON_UNIQUE) = 0
           AND COUNT(*) = 2
           AND GROUP_CONCAT(COALESCE(COLUMN_NAME, '<expression>')
                            ORDER BY SEQ_IN_INDEX SEPARATOR ',') = 'document_id,user_id'
           AND SUM(CASE WHEN SUB_PART IS NOT NULL THEN 1 ELSE 0 END) = 0
           AND SUM(CASE WHEN COLUMN_NAME IS NULL OR EXPRESSION IS NOT NULL THEN 1 ELSE 0 END) = 0
    ) unique_chunk_owner_candidate;
    SET v_blockers = v_blockers + v_wrong_uniqueness;
    SET @rag_need_chunk_owner_index = IF(v_structural_matches = 0, 1, 0);

    -- Exact composite FK, under any name, with matching tenant columns and ON DELETE CASCADE.
    SELECT COUNT(*) INTO v_structural_matches
    FROM (
        SELECT k.CONSTRAINT_NAME
        FROM information_schema.KEY_COLUMN_USAGE k
        JOIN information_schema.REFERENTIAL_CONSTRAINTS r
          ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
         AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME
         AND r.TABLE_NAME = k.TABLE_NAME
        WHERE k.CONSTRAINT_SCHEMA = DATABASE()
          AND k.TABLE_NAME = 'knowledge_chunks'
        GROUP BY k.CONSTRAINT_NAME
        HAVING COUNT(*) = 2
           AND COUNT(DISTINCT k.ORDINAL_POSITION) = 2
           AND MIN(k.ORDINAL_POSITION) = 1
           AND MAX(k.ORDINAL_POSITION) = 2
           AND GROUP_CONCAT(k.COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ',')
               = 'document_id,user_id'
           AND GROUP_CONCAT(k.REFERENCED_COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ',')
               = 'id,user_id'
           AND MAX(k.REFERENCED_TABLE_SCHEMA) = DATABASE()
           AND MAX(k.REFERENCED_TABLE_NAME) = 'knowledge_documents'
           AND MAX(r.DELETE_RULE) = 'CASCADE'
           AND MAX(r.UPDATE_RULE) IN ('NO ACTION', 'RESTRICT')
    ) exact_owner_foreign_key;
    SELECT COUNT(*) INTO v_named_exists
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'knowledge_chunks'
      AND CONSTRAINT_NAME = 'fk_knowledge_chunks_document_user';
    SELECT COUNT(*) INTO v_named_matches
    FROM (
        SELECT k.CONSTRAINT_NAME
        FROM information_schema.KEY_COLUMN_USAGE k
        JOIN information_schema.REFERENTIAL_CONSTRAINTS r
          ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
         AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME
         AND r.TABLE_NAME = k.TABLE_NAME
        WHERE k.CONSTRAINT_SCHEMA = DATABASE()
          AND k.TABLE_NAME = 'knowledge_chunks'
          AND k.CONSTRAINT_NAME = 'fk_knowledge_chunks_document_user'
        GROUP BY k.CONSTRAINT_NAME
        HAVING COUNT(*) = 2
           AND GROUP_CONCAT(k.COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ',')
               = 'document_id,user_id'
           AND GROUP_CONCAT(k.REFERENCED_COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ',')
               = 'id,user_id'
           AND MAX(k.REFERENCED_TABLE_SCHEMA) = DATABASE()
           AND MAX(k.REFERENCED_TABLE_NAME) = 'knowledge_documents'
           AND MAX(r.DELETE_RULE) = 'CASCADE'
           AND MAX(r.UPDATE_RULE) IN ('NO ACTION', 'RESTRICT')
    ) exact_named_owner_foreign_key;
    IF v_named_exists > 0 AND v_named_matches <> 1 THEN
        SET v_blockers = v_blockers + 1;
    END IF;
    SELECT COUNT(*) INTO v_wrong_foreign_keys
    FROM (
        SELECT k.CONSTRAINT_NAME
        FROM information_schema.KEY_COLUMN_USAGE k
        JOIN information_schema.REFERENTIAL_CONSTRAINTS r
          ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
         AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME
         AND r.TABLE_NAME = k.TABLE_NAME
        WHERE k.CONSTRAINT_SCHEMA = DATABASE()
          AND k.TABLE_NAME = 'knowledge_chunks'
        GROUP BY k.CONSTRAINT_NAME
        HAVING COUNT(*) = 2
           AND GROUP_CONCAT(k.COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ',')
               = 'document_id,user_id'
           AND NOT (
               GROUP_CONCAT(k.REFERENCED_COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ',')
                   = 'id,user_id'
               AND MAX(k.REFERENCED_TABLE_SCHEMA) = DATABASE()
               AND MAX(k.REFERENCED_TABLE_NAME) = 'knowledge_documents'
               AND MAX(r.DELETE_RULE) = 'CASCADE'
               AND MAX(r.UPDATE_RULE) IN ('NO ACTION', 'RESTRICT')
           )
    ) wrong_owner_foreign_key;
    SET v_blockers = v_blockers + v_wrong_foreign_keys;
    SET @rag_need_owner_foreign_key = IF(v_structural_matches = 0, 1, 0);

    IF v_blockers > 0 THEN
        SET v_message = CONCAT('RAG safety migration blocked by index or foreign-key definition; count=',
                               v_blockers, '. Review the precheck; do not DROP unknown objects.');
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_message;
    END IF;

    SET @rag_metadata_validated = 1;
END$$

DROP PROCEDURE IF EXISTS rag_safety_apply_constraints$$
CREATE PROCEDURE rag_safety_apply_constraints()
BEGIN
    DECLARE v_message VARCHAR(255);

    IF COALESCE(@rag_metadata_validated, 0) <> 1
       OR @rag_need_parent_unique IS NULL
       OR @rag_need_chunk_unique IS NULL
       OR @rag_need_chunk_owner_index IS NULL
       OR @rag_need_owner_foreign_key IS NULL THEN
        SIGNAL SQLSTATE '45000'
          SET MESSAGE_TEXT = 'RAG safety metadata plan is missing; no constraints were applied.';
    END IF;

    IF @rag_need_parent_unique = 1 THEN
        ALTER TABLE knowledge_documents
          ADD UNIQUE KEY uk_knowledge_documents_id_user_id (id, user_id);
    END IF;

    IF @rag_need_chunk_unique = 1 THEN
        ALTER TABLE knowledge_chunks
          ADD UNIQUE KEY uk_knowledge_chunks_document_chunk_index (document_id, chunk_index);
    END IF;

    IF @rag_need_chunk_owner_index = 1 THEN
        ALTER TABLE knowledge_chunks
          ADD KEY idx_knowledge_chunks_document_user (document_id, user_id);
    END IF;

    IF @rag_need_owner_foreign_key = 1 THEN
        ALTER TABLE knowledge_chunks
          ADD CONSTRAINT fk_knowledge_chunks_document_user
          FOREIGN KEY (document_id, user_id)
          REFERENCES knowledge_documents (id, user_id)
          ON DELETE CASCADE;
    END IF;

    -- The same metadata SQL already succeeded before DELETE. Reuse it to assert the final schema.
    CALL rag_safety_plan_constraints();
    IF @rag_need_parent_unique <> 0
       OR @rag_need_chunk_unique <> 0
       OR @rag_need_chunk_owner_index <> 0
       OR @rag_need_owner_foreign_key <> 0 THEN
        SET v_message = 'RAG safety constraints are incomplete after DDL application.';
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_message;
    END IF;
END$$

-- No table data mutation occurs until both the data guard and every metadata query have succeeded.
CALL rag_safety_guard(FALSE)$$
CALL rag_safety_plan_constraints()$$

-- The only automatic cleanup: rows whose matched parent lifecycle explicitly makes them invalid.
DELETE c
FROM knowledge_chunks c
JOIN knowledge_documents d
  ON d.id = c.document_id
 AND d.user_id = c.user_id
WHERE d.status IN ('FAILED', 'PROCESSING')$$

-- Recheck data after cleanup, then apply only DDL steps planned before cleanup.
CALL rag_safety_guard(TRUE)$$
CALL rag_safety_apply_constraints()$$

DROP PROCEDURE rag_safety_apply_constraints$$
DROP PROCEDURE rag_safety_plan_constraints$$
DROP PROCEDURE rag_safety_guard$$

DELIMITER ;

SELECT 'rag_safety_consistency migration completed' AS migration_status;
