package com.example.server.service;

import com.example.server.entity.KnowledgeChunk;
import com.example.server.mapper.KnowledgeChunkMapper;
import com.example.server.mapper.KnowledgeDocumentMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Opt-in verification for a disposable MySQL 8.0.46 database only.
 *
 * <p>Example activation (never point this at a database that must be retained):
 * {@code -Drag.mysql.integration=true
 * -Drag.mysql.url=jdbc:mysql://localhost:3306/disposable_rag_it
 * -Drag.mysql.username=root -Drag.mysql.password=...}</p>
 */
@EnabledIfSystemProperty(named = "rag.mysql.integration", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgePersistenceMySql8046IntegrationTest {

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private KnowledgePersistenceService persistenceService;
    private KnowledgeChunkMapper chunkMapper;

    @BeforeAll
    void connectOnlyToExplicitDisposableMySql8046Database() {
        context = new AnnotationConfigApplicationContext(MySqlIntegrationConfiguration.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));

        String databaseName = jdbc.queryForObject("SELECT DATABASE()", String.class);
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        if (databaseName == null || !databaseName.endsWith("_rag_it")) {
            throw new IllegalStateException("Integration database name must end with _rag_it");
        }
        if (version == null || !version.startsWith("8.0.46")) {
            throw new IllegalStateException("Integration database must be MySQL 8.0.46");
        }

        recreateSchema();
        persistenceService = context.getBean(KnowledgePersistenceService.class);
        chunkMapper = context.getBean(KnowledgeChunkMapper.class);
    }

    @BeforeEach
    void resetData() {
        jdbc.execute("DROP TRIGGER IF EXISTS rag_it_reject_ready");
        jdbc.execute("DELETE FROM knowledge_chunks");
        jdbc.execute("DELETE FROM knowledge_documents");
    }

    @AfterAll
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void readyCasZeroRollsBackSuccessfulBatchInsert() {
        insertDocument(1L, 20L, "PROCESSING");
        jdbc.execute("""
                CREATE TRIGGER rag_it_reject_ready
                BEFORE INSERT ON knowledge_chunks
                FOR EACH ROW
                UPDATE knowledge_documents SET status = 'FAILED' WHERE id = NEW.document_id
                """);

        assertThrows(KnowledgePersistenceService.ProcessingStateException.class,
                () -> persistenceService.persistPreparedChunks(1L, 20L, List.of(chunk(1L, 20L, 0))));

        assertEquals(0, chunkCount(1L));
        assertEquals("PROCESSING", documentStatus(1L));
    }

    @Test
    void failingMultiRowInsertLeavesNoPartialChunks() {
        insertDocument(1L, 20L, "PROCESSING");
        KnowledgeChunk invalid = chunk(1L, 20L, 1);
        invalid.setContent(null);

        assertThrows(RuntimeException.class,
                () -> persistenceService.persistPreparedChunks(
                        1L, 20L, List.of(chunk(1L, 20L, 0), invalid)));

        assertEquals(0, chunkCount(1L));
        assertEquals("PROCESSING", documentStatus(1L));
    }

    @Test
    void realRetrievalSqlReturnsOnlyReadyCurrentTenantWithMatchingParent() throws Exception {
        insertDocument(1L, 20L, "READY");
        insertDocument(2L, 20L, "FAILED");
        insertDocument(3L, 20L, "PROCESSING");
        insertDocument(4L, 99L, "READY");
        insertChunkDirect(101L, 1L, 20L, 0);
        insertChunkDirect(102L, 2L, 20L, 0);
        insertChunkDirect(103L, 3L, 20L, 0);
        insertChunkDirect(104L, 4L, 99L, 0);
        insertWithoutForeignKeyChecks(105L, 1L, 99L, 1);
        insertWithoutForeignKeyChecks(106L, 999L, 20L, 0);

        List<KnowledgeChunk> results = chunkMapper.selectRetrievableByUser(20L);

        assertEquals(List.of(101L), results.stream().map(KnowledgeChunk::getId).toList());
    }

    @Test
    void uniqueDocumentChunkIndexRejectsDuplicate() {
        insertDocument(1L, 20L, "READY");
        insertChunkDirect(101L, 1L, 20L, 0);

        assertThrows(DataAccessException.class,
                () -> insertChunkDirect(102L, 1L, 20L, 0));
    }

    @Test
    void compositeForeignKeyRejectsOwnerMismatchAndCascadeDeletesChildren() {
        insertDocument(1L, 20L, "READY");

        assertThrows(DataAccessException.class,
                () -> insertChunkDirect(101L, 1L, 99L, 0));

        insertChunkDirect(102L, 1L, 20L, 0);
        jdbc.update("DELETE FROM knowledge_documents WHERE id = ? AND user_id = ?", 1L, 20L);
        assertEquals(0, chunkCount(1L));
    }

    private void recreateSchema() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        jdbc.execute("DROP TABLE IF EXISTS knowledge_chunks");
        jdbc.execute("DROP TABLE IF EXISTS knowledge_documents");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        jdbc.execute("""
                CREATE TABLE knowledge_documents (
                  id BIGINT NOT NULL,
                  user_id BIGINT NOT NULL,
                  original_filename VARCHAR(512) NOT NULL DEFAULT 'it.txt',
                  file_ext VARCHAR(20) NOT NULL DEFAULT 'txt',
                  minio_bucket VARCHAR(100) NOT NULL DEFAULT 'it',
                  minio_object_key VARCHAR(1024) NOT NULL DEFAULT 'it',
                  status VARCHAR(50) NOT NULL,
                  chunk_count INT NOT NULL DEFAULT 0,
                  error_message VARCHAR(2048),
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_knowledge_documents_id_user_id (id, user_id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE knowledge_chunks (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  document_id BIGINT NOT NULL,
                  user_id BIGINT NOT NULL,
                  chunk_index INT NOT NULL,
                  content LONGTEXT NOT NULL,
                  content_hash VARCHAR(64),
                  char_count INT,
                  embedding LONGTEXT,
                  embedding_model VARCHAR(100),
                  embedding_dim INT,
                  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_knowledge_chunks_document_chunk_index (document_id, chunk_index),
                  KEY idx_knowledge_chunks_document_user (document_id, user_id),
                  CONSTRAINT fk_knowledge_chunks_document_user
                    FOREIGN KEY (document_id, user_id)
                    REFERENCES knowledge_documents (id, user_id)
                    ON DELETE CASCADE
                ) ENGINE=InnoDB
                """);
    }

    private void insertDocument(long id, long userId, String status) {
        jdbc.update("""
                INSERT INTO knowledge_documents (id, user_id, status, chunk_count)
                VALUES (?, ?, ?, 0)
                """, id, userId, status);
    }

    private void insertChunkDirect(long id, long documentId, long userId, int chunkIndex) {
        jdbc.update("""
                INSERT INTO knowledge_chunks
                    (id, document_id, user_id, chunk_index, content, embedding,
                     embedding_model, embedding_dim)
                VALUES (?, ?, ?, ?, 'content', '[1.0,0.0]', 'it-model', 2)
                """, id, documentId, userId, chunkIndex);
    }

    private void insertWithoutForeignKeyChecks(long id, long documentId, long userId, int chunkIndex)
            throws Exception {
        DataSource dataSource = context.getBean(DataSource.class);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            try (java.sql.PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO knowledge_chunks
                        (id, document_id, user_id, chunk_index, content, embedding,
                         embedding_model, embedding_dim)
                    VALUES (?, ?, ?, ?, 'content', '[1.0,0.0]', 'it-model', 2)
                    """)) {
                insert.setLong(1, id);
                insert.setLong(2, documentId);
                insert.setLong(3, userId);
                insert.setInt(4, chunkIndex);
                insert.executeUpdate();
            } finally {
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        }
    }

    private int chunkCount(long documentId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_chunks WHERE document_id = ?",
                Integer.class, documentId);
    }

    private String documentStatus(long documentId) {
        return jdbc.queryForObject(
                "SELECT status FROM knowledge_documents WHERE id = ?",
                String.class, documentId);
    }

    private KnowledgeChunk chunk(long documentId, long userId, int index) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setDocumentId(documentId);
        chunk.setUserId(userId);
        chunk.setChunkIndex(index);
        chunk.setContent("content " + index);
        chunk.setContentHash("hash-" + index);
        chunk.setCharCount(chunk.getContent().length());
        chunk.setEmbedding("[1.0,0.0]");
        chunk.setEmbeddingModel("it-model");
        chunk.setEmbeddingDim(2);
        return chunk;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class MySqlIntegrationConfiguration {

        @Bean
        DataSource dataSource() {
            String url = requiredProperty("rag.mysql.url");
            String username = requiredProperty("rag.mysql.username");
            String password = System.getProperty("rag.mysql.password", "");
            return new DriverManagerDataSource(url, username, password);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            org.apache.ibatis.session.Configuration configuration =
                    new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }

        @Bean
        KnowledgeDocumentMapper knowledgeDocumentMapper(SqlSessionFactory sqlSessionFactory)
                throws Exception {
            return mapper(KnowledgeDocumentMapper.class, sqlSessionFactory);
        }

        @Bean
        KnowledgeChunkMapper knowledgeChunkMapper(SqlSessionFactory sqlSessionFactory)
                throws Exception {
            return mapper(KnowledgeChunkMapper.class, sqlSessionFactory);
        }

        @Bean
        KnowledgePersistenceService knowledgePersistenceService(
                KnowledgeDocumentMapper documentMapper,
                KnowledgeChunkMapper chunkMapper) {
            return new KnowledgePersistenceService(documentMapper, chunkMapper);
        }

        private static <T> T mapper(Class<T> mapperType, SqlSessionFactory sqlSessionFactory)
                throws Exception {
            MapperFactoryBean<T> factory = new MapperFactoryBean<>(mapperType);
            factory.setSqlSessionFactory(sqlSessionFactory);
            factory.afterPropertiesSet();
            return factory.getObject();
        }

        private static String requiredProperty(String name) {
            String value = System.getProperty(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Missing required system property: " + name);
            }
            return value;
        }
    }
}
