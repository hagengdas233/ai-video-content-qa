-- RAG safety/consistency read-only precheck for MySQL 8.0.46.
-- This file intentionally contains SELECT statements only. It does not mutate data or schema.
-- Run it against a backup-verified target and review every result before running the migration.

SELECT 'orphan_chunks' AS check_name, COUNT(*) AS blocking_count
FROM knowledge_chunks c
LEFT JOIN knowledge_documents d ON d.id = c.document_id
WHERE d.id IS NULL;

SELECT c.id, c.document_id, c.user_id, c.chunk_index
FROM knowledge_chunks c
LEFT JOIN knowledge_documents d ON d.id = c.document_id
WHERE d.id IS NULL
ORDER BY c.document_id, c.chunk_index, c.id;

SELECT 'chunk_document_user_mismatch' AS check_name, COUNT(*) AS blocking_count
FROM knowledge_chunks c
JOIN knowledge_documents d ON d.id = c.document_id
WHERE c.user_id <> d.user_id;

SELECT c.id, c.document_id, c.user_id AS chunk_user_id,
       d.user_id AS document_user_id, c.chunk_index
FROM knowledge_chunks c
JOIN knowledge_documents d ON d.id = c.document_id
WHERE c.user_id <> d.user_id
ORDER BY c.document_id, c.chunk_index, c.id;

SELECT 'failed_processing_residual_chunks' AS check_name,
       COUNT(*) AS lifecycle_cleanup_candidate_count
FROM knowledge_chunks c
JOIN knowledge_documents d
  ON d.id = c.document_id
 AND d.user_id = c.user_id
WHERE d.status IN ('FAILED', 'PROCESSING');

SELECT d.status, COUNT(*) AS residual_chunk_count
FROM knowledge_chunks c
JOIN knowledge_documents d
  ON d.id = c.document_id
 AND d.user_id = c.user_id
WHERE d.status IN ('FAILED', 'PROCESSING')
GROUP BY d.status
ORDER BY d.status;

SELECT 'ready_duplicate_document_chunk_index' AS check_name,
       COUNT(*) AS blocking_group_count
FROM (
    SELECT c.document_id, c.chunk_index
    FROM knowledge_chunks c
    JOIN knowledge_documents d
      ON d.id = c.document_id
     AND d.user_id = c.user_id
    WHERE d.status = 'READY'
    GROUP BY c.document_id, c.chunk_index
    HAVING COUNT(*) > 1
) duplicate_groups;

SELECT c.document_id, c.chunk_index, COUNT(*) AS duplicate_count,
       GROUP_CONCAT(c.id ORDER BY c.id) AS chunk_ids
FROM knowledge_chunks c
JOIN knowledge_documents d
  ON d.id = c.document_id
 AND d.user_id = c.user_id
WHERE d.status = 'READY'
GROUP BY c.document_id, c.chunk_index
HAVING COUNT(*) > 1
ORDER BY c.document_id, c.chunk_index;

SELECT TABLE_NAME, ENGINE, TABLE_COLLATION
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('knowledge_documents', 'knowledge_chunks')
ORDER BY TABLE_NAME;

SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, EXTRA
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND (
       (TABLE_NAME = 'knowledge_documents' AND COLUMN_NAME IN ('id', 'user_id'))
    OR (TABLE_NAME = 'knowledge_chunks' AND COLUMN_NAME IN ('id', 'document_id', 'user_id', 'chunk_index'))
  )
ORDER BY TABLE_NAME, ORDINAL_POSITION;

SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME,
       SUB_PART, EXPRESSION, INDEX_TYPE, IS_VISIBLE
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('knowledge_documents', 'knowledge_chunks')
  AND (
       INDEX_NAME IN (
           'uk_knowledge_documents_id_user_id',
           'uk_knowledge_chunks_document_chunk_index',
           'idx_knowledge_chunks_document_user'
       )
       OR (TABLE_NAME = 'knowledge_documents' AND COLUMN_NAME IN ('id', 'user_id'))
       OR (TABLE_NAME = 'knowledge_chunks' AND COLUMN_NAME IN ('document_id', 'user_id', 'chunk_index'))
  )
ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;

SELECT TABLE_NAME, INDEX_NAME, MIN(NON_UNIQUE) AS minimum_non_unique,
       COUNT(*) AS index_column_count,
       GROUP_CONCAT(COALESCE(COLUMN_NAME, '<expression>')
                    ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS indexed_columns,
       SUM(CASE WHEN SUB_PART IS NOT NULL THEN 1 ELSE 0 END) AS prefix_column_count,
       SUM(CASE WHEN COLUMN_NAME IS NULL OR EXPRESSION IS NOT NULL THEN 1 ELSE 0 END)
           AS expression_column_count
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('knowledge_documents', 'knowledge_chunks')
GROUP BY TABLE_NAME, INDEX_NAME
ORDER BY TABLE_NAME, INDEX_NAME;

SELECT k.CONSTRAINT_NAME, k.TABLE_NAME,
       GROUP_CONCAT(k.COLUMN_NAME ORDER BY k.ORDINAL_POSITION) AS child_columns,
       k.REFERENCED_TABLE_NAME,
       GROUP_CONCAT(k.REFERENCED_COLUMN_NAME ORDER BY k.ORDINAL_POSITION) AS parent_columns,
       r.UPDATE_RULE, r.DELETE_RULE
FROM information_schema.KEY_COLUMN_USAGE k
JOIN information_schema.REFERENTIAL_CONSTRAINTS r
  ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA
 AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME
 AND r.TABLE_NAME = k.TABLE_NAME
WHERE k.CONSTRAINT_SCHEMA = DATABASE()
  AND k.TABLE_NAME = 'knowledge_chunks'
  AND k.REFERENCED_TABLE_NAME IS NOT NULL
GROUP BY k.CONSTRAINT_NAME, k.TABLE_NAME, k.REFERENCED_TABLE_NAME,
         r.UPDATE_RULE, r.DELETE_RULE
ORDER BY k.CONSTRAINT_NAME;
