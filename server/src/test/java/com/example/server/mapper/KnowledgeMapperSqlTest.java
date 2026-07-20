package com.example.server.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class KnowledgeMapperSqlTest {

    @Test
    void retrievalJoinsReadyOwnedParentAndRejectsOrphansAndOwnerMismatch() throws Exception {
        Method method = KnowledgeChunkMapper.class.getMethod("selectRetrievableByUser", Long.class);
        String sql = normalize(method.getAnnotation(Select.class).value()).toLowerCase();

        assertTrue(sql.contains("inner join knowledge_documents d"));
        assertTrue(sql.contains("c.document_id = d.id"));
        assertTrue(sql.contains("c.user_id = d.user_id"));
        assertTrue(sql.contains("c.user_id = #{userid}"));
        assertTrue(sql.contains("d.user_id = #{userid}"));
        assertTrue(sql.contains("d.status = 'ready'"));
        assertTrue(sql.contains("c.embedding is not null"));
        assertTrue(sql.contains("c.embedding <> ''"));
        assertFalse(sql.contains(" or "));
    }

    @Test
    void readyTransitionUsesDocumentOwnerAndExpectedProcessingState() throws Exception {
        Method method = KnowledgeDocumentMapper.class.getMethod(
                "markReadyIfProcessing", Long.class, Long.class, int.class, LocalDateTime.class);
        String sql = normalize(method.getAnnotation(Update.class).value()).toLowerCase();

        assertTrue(sql.contains("where id = #{documentid}"));
        assertTrue(sql.contains("user_id = #{userid}"));
        assertTrue(sql.contains("status = 'processing'"));
        assertTrue(sql.contains("status = 'ready'"));
        assertTrue(sql.contains("chunk_count = #{chunkcount}"));
    }

    @Test
    void failedTransitionUsesDocumentOwnerAndExpectedProcessingState() throws Exception {
        Method method = KnowledgeDocumentMapper.class.getMethod(
                "markFailedIfProcessing", Long.class, Long.class, String.class, LocalDateTime.class);
        String sql = normalize(method.getAnnotation(Update.class).value()).toLowerCase();

        assertTrue(sql.contains("where id = #{documentid}"));
        assertTrue(sql.contains("user_id = #{userid}"));
        assertTrue(sql.contains("status = 'processing'"));
        assertTrue(sql.contains("status = 'failed'"));
        assertTrue(sql.contains("chunk_count = 0"));
    }

    @Test
    void chunksUseOneMultiRowInsertStatement() throws Exception {
        Method method = KnowledgeChunkMapper.class.getMethod("insertBatch", List.class);
        String sql = normalize(method.getAnnotation(Insert.class).value()).toLowerCase();

        assertTrue(sql.contains("insert into knowledge_chunks"));
        assertTrue(sql.contains("<foreach collection='chunks'"));
        assertTrue(sql.contains("separator=','"));
    }

    @Test
    void documentReadAndDeleteAreBothTenantScopedAndDeleteOnlyTerminalStates() throws Exception {
        Method selectMethod = KnowledgeDocumentMapper.class.getMethod(
                "selectOwnedById", Long.class, Long.class);
        String selectSql = normalize(selectMethod.getAnnotation(Select.class).value()).toLowerCase();
        assertTrue(selectSql.contains("id = #{documentid}"));
        assertTrue(selectSql.contains("user_id = #{userid}"));

        Method deleteMethod = KnowledgeDocumentMapper.class.getMethod(
                "deleteOwnedReadyOrFailed", Long.class, Long.class);
        String deleteSql = normalize(deleteMethod
                .getAnnotation(org.apache.ibatis.annotations.Delete.class).value()).toLowerCase();
        assertTrue(deleteSql.contains("id = #{documentid}"));
        assertTrue(deleteSql.contains("user_id = #{userid}"));
        assertTrue(deleteSql.contains("status in ('ready', 'failed')"));
    }

    private String normalize(String[] sql) {
        return String.join(" ", sql).replaceAll("\\s+", " ").trim();
    }
}
