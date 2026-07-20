package com.example.server.mapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "rag.mysql.integration", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagMigrationMySql8046IntegrationTest {

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private List<String> migrationStatements;

    @BeforeAll
    void connectOnlyToDisposableMySql8046Database() throws IOException {
        dataSource = new DriverManagerDataSource(
                requiredProperty("rag.mysql.url"),
                requiredProperty("rag.mysql.username"),
                System.getProperty("rag.mysql.password", ""));
        jdbc = new JdbcTemplate(dataSource);

        String databaseName = jdbc.queryForObject("SELECT DATABASE()", String.class);
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        if (databaseName == null || !databaseName.endsWith("_rag_it")) {
            throw new IllegalStateException("Migration integration database name must end with _rag_it");
        }
        if (version == null || !version.startsWith("8.0.46")) {
            throw new IllegalStateException("Migration integration database must be MySQL 8.0.46");
        }
        migrationStatements = parseMigration(readSql("migrate_rag_safety_consistency.sql"));
    }

    @BeforeEach
    void recreateOldSchema() {
        jdbc.execute("DROP PROCEDURE IF EXISTS rag_safety_apply_constraints");
        jdbc.execute("DROP PROCEDURE IF EXISTS rag_safety_plan_constraints");
        jdbc.execute("DROP PROCEDURE IF EXISTS rag_safety_guard");
        jdbc.execute("DROP TABLE IF EXISTS knowledge_chunks");
        jdbc.execute("DROP TABLE IF EXISTS knowledge_documents");
        jdbc.execute("""
                CREATE TABLE knowledge_documents (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  user_id BIGINT NOT NULL,
                  status VARCHAR(50) NOT NULL,
                  chunk_count INT DEFAULT 0,
                  PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE knowledge_chunks (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  document_id BIGINT NOT NULL,
                  user_id BIGINT NOT NULL,
                  chunk_index INT NOT NULL,
                  content LONGTEXT NOT NULL,
                  embedding LONGTEXT NULL,
                  PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
    }

    @Test
    void scenarioALegalReadyDataIsUnchangedAndAllConstraintsAreCreated() throws Exception {
        insertDocument(1, 20, "READY", 1);
        insertChunk(1, 1, 20, 0, "legal-ready");

        executeMigration();

        assertAllTargetStructuresPresent();
        assertEquals(1, count("knowledge_chunks"));
        assertEquals("legal-ready", jdbc.queryForObject(
                "SELECT content FROM knowledge_chunks WHERE id=1", String.class));
    }

    @Test
    void scenarioEOnlyLifecycleResidualsAreDeletedAndReadyDataIsPreserved() throws Exception {
        insertDocument(1, 20, "READY", 1);
        insertDocument(2, 20, "FAILED", 1);
        insertDocument(3, 20, "PROCESSING", 1);
        insertChunk(1, 1, 20, 0, "legal-ready");
        insertChunk(2, 2, 20, 0, "failed-residual");
        insertChunk(3, 3, 20, 0, "processing-residual");

        executeMigration();

        assertAllTargetStructuresPresent();
        assertEquals(1, count("knowledge_chunks"));
        assertEquals("legal-ready", jdbc.queryForObject(
                "SELECT content FROM knowledge_chunks", String.class));
    }

    @ParameterizedTest(name = "correct partial state: {0}")
    @MethodSource("correctPartialStates")
    void scenarioFCorrectPartialStatesAreDetectedAndCompleted(String name, String ddl) throws Exception {
        insertDocument(1, 20, "READY", 1);
        insertChunk(1, 1, 20, 0, "legal-ready");
        executeSqlStatements(ddl);

        executeMigration();

        assertAllTargetStructuresPresent();
        assertEquals(1, count("knowledge_chunks"));
    }

    @Test
    void scenarioFCompleteSchemaCanBeRerunIdempotently() throws Exception {
        insertDocument(1, 20, "READY", 1);
        insertChunk(1, 1, 20, 0, "legal-ready");

        executeMigration();
        executeMigration();

        assertAllTargetStructuresPresent();
        assertEquals(1, count("knowledge_chunks"));
        assertEquals("legal-ready", jdbc.queryForObject(
                "SELECT content FROM knowledge_chunks", String.class));
    }

    @ParameterizedTest(name = "wrong definition: {0}")
    @MethodSource("wrongDefinitions")
    void wrongDefinitionsSignalBeforeLifecycleCleanup(String name, String ddl) throws Exception {
        executeSqlStatements(ddl);
        insertDocument(20, 20, "FAILED", 1);
        insertChunk(20, 20, 20, 0, "must-remain");
        String schemaBefore = schemaSignature();

        SQLException exception = assertThrows(SQLException.class, this::executeMigration);

        assertEquals("45000", exception.getSQLState());
        assertEquals(1, count("knowledge_chunks"));
        assertEquals("must-remain", jdbc.queryForObject(
                "SELECT content FROM knowledge_chunks", String.class));
        assertEquals(schemaBefore, schemaSignature());
    }

    @ParameterizedTest(name = "blocking data: {0}")
    @MethodSource("blockingData")
    void orphanMismatchAndReadyDuplicateSignalWithoutMutation(
            String name, String setupSql, int expectedChunkCount) throws Exception {
        executeSqlStatements(setupSql);
        String schemaBefore = schemaSignature();

        SQLException exception = assertThrows(SQLException.class, this::executeMigration);

        assertEquals("45000", exception.getSQLState());
        assertEquals(expectedChunkCount, count("knowledge_chunks"));
        assertEquals(schemaBefore, schemaSignature());
    }

    static Stream<Arguments> correctPartialStates() {
        return Stream.of(
                Arguments.of("expected parent unique", """
                        ALTER TABLE knowledge_documents
                          ADD UNIQUE KEY uk_knowledge_documents_id_user_id(id,user_id)
                        """),
                Arguments.of("differently named parent unique", """
                        ALTER TABLE knowledge_documents
                          ADD UNIQUE KEY existing_parent_owner_unique(id,user_id)
                        """),
                Arguments.of("chunk unique", """
                        ALTER TABLE knowledge_chunks
                          ADD UNIQUE KEY uk_knowledge_chunks_document_chunk_index(document_id,chunk_index)
                        """),
                Arguments.of("ordinary owner index", """
                        ALTER TABLE knowledge_chunks
                          ADD KEY idx_knowledge_chunks_document_user(document_id,user_id)
                        """),
                Arguments.of("differently named correct foreign key", """
                        ALTER TABLE knowledge_documents
                          ADD UNIQUE KEY existing_parent_owner_unique(id,user_id);
                        ALTER TABLE knowledge_chunks
                          ADD CONSTRAINT existing_owner_foreign_key
                          FOREIGN KEY(document_id,user_id)
                          REFERENCES knowledge_documents(id,user_id)
                          ON DELETE CASCADE
                        """),
                Arguments.of("multiple completed steps", """
                        ALTER TABLE knowledge_documents
                          ADD UNIQUE KEY existing_parent_owner_unique(id,user_id);
                        ALTER TABLE knowledge_chunks
                          ADD UNIQUE KEY existing_document_chunk_unique(document_id,chunk_index);
                        ALTER TABLE knowledge_chunks
                          ADD KEY existing_document_owner_index(document_id,user_id)
                        """),
                Arguments.of("all expected structures", """
                        ALTER TABLE knowledge_documents
                          ADD UNIQUE KEY uk_knowledge_documents_id_user_id(id,user_id);
                        ALTER TABLE knowledge_chunks
                          ADD UNIQUE KEY uk_knowledge_chunks_document_chunk_index(document_id,chunk_index);
                        ALTER TABLE knowledge_chunks
                          ADD KEY idx_knowledge_chunks_document_user(document_id,user_id);
                        ALTER TABLE knowledge_chunks
                          ADD CONSTRAINT fk_knowledge_chunks_document_user
                          FOREIGN KEY(document_id,user_id)
                          REFERENCES knowledge_documents(id,user_id)
                          ON DELETE CASCADE
                        """));
    }

    static Stream<Arguments> wrongDefinitions() {
        return Stream.of(
                Arguments.of("same parent name wrong order", """
                        ALTER TABLE knowledge_documents
                          ADD UNIQUE KEY uk_knowledge_documents_id_user_id(user_id,id)
                        """),
                Arguments.of("same chunk name wrong order", """
                        ALTER TABLE knowledge_chunks
                          ADD UNIQUE KEY uk_knowledge_chunks_document_chunk_index(chunk_index,document_id)
                        """),
                Arguments.of("same chunk name extra column", """
                        ALTER TABLE knowledge_chunks
                          ADD UNIQUE KEY uk_knowledge_chunks_document_chunk_index
                          (document_id,chunk_index,user_id)
                        """),
                Arguments.of("parent candidate is not unique", """
                        ALTER TABLE knowledge_documents
                          ADD KEY existing_parent_owner_non_unique(id,user_id)
                        """),
                Arguments.of("chunk unique candidate is not unique", """
                        ALTER TABLE knowledge_chunks
                          ADD KEY existing_document_chunk_non_unique(document_id,chunk_index)
                        """),
                Arguments.of("ordinary owner index is unexpectedly unique", """
                        ALTER TABLE knowledge_chunks
                          ADD UNIQUE KEY existing_owner_unique(document_id,user_id)
                        """),
                Arguments.of("same ordinary index name wrong order", """
                        ALTER TABLE knowledge_chunks
                          ADD KEY idx_knowledge_chunks_document_user(user_id,document_id)
                        """),
                Arguments.of("same ordinary index name extra column", """
                        ALTER TABLE knowledge_chunks
                          ADD KEY idx_knowledge_chunks_document_user(document_id,user_id,chunk_index)
                        """),
                Arguments.of("same ordinary index name uses prefix", """
                        ALTER TABLE knowledge_chunks
                          ADD KEY idx_knowledge_chunks_document_user(content(16),document_id)
                        """),
                Arguments.of("same ordinary index name uses expression", """
                        CREATE INDEX idx_knowledge_chunks_document_user
                          ON knowledge_chunks ((document_id + user_id))
                        """),
                Arguments.of("foreign key references wrong parent order", """
                        ALTER TABLE knowledge_documents
                          ADD UNIQUE KEY existing_wrong_parent_order(user_id,id);
                        ALTER TABLE knowledge_chunks
                          ADD CONSTRAINT fk_knowledge_chunks_document_user
                          FOREIGN KEY(document_id,user_id)
                          REFERENCES knowledge_documents(user_id,id)
                          ON DELETE CASCADE
                        """),
                Arguments.of("foreign key is not cascade", """
                        ALTER TABLE knowledge_documents
                          ADD UNIQUE KEY existing_parent_owner_unique(id,user_id);
                        ALTER TABLE knowledge_chunks
                          ADD CONSTRAINT fk_knowledge_chunks_document_user
                          FOREIGN KEY(document_id,user_id)
                          REFERENCES knowledge_documents(id,user_id)
                          ON DELETE RESTRICT
                        """),
                Arguments.of("same foreign key name is single column", """
                        ALTER TABLE knowledge_chunks
                          ADD CONSTRAINT fk_knowledge_chunks_document_user
                          FOREIGN KEY(document_id)
                          REFERENCES knowledge_documents(id)
                          ON DELETE CASCADE
                        """));
    }

    static Stream<Arguments> blockingData() {
        return Stream.of(
                Arguments.of("orphan", """
                        INSERT INTO knowledge_chunks
                          (id,document_id,user_id,chunk_index,content,embedding)
                        VALUES(1,999,20,0,'orphan','[1.0]')
                        """, 1),
                Arguments.of("tenant mismatch", """
                        INSERT INTO knowledge_documents(id,user_id,status,chunk_count)
                        VALUES(1,20,'READY',1);
                        INSERT INTO knowledge_chunks
                          (id,document_id,user_id,chunk_index,content,embedding)
                        VALUES(1,1,99,0,'mismatch','[1.0]')
                        """, 1),
                Arguments.of("ready duplicate", """
                        INSERT INTO knowledge_documents(id,user_id,status,chunk_count)
                        VALUES(1,20,'READY',2);
                        INSERT INTO knowledge_chunks
                          (id,document_id,user_id,chunk_index,content,embedding)
                        VALUES(1,1,20,0,'one','[1.0]'),(2,1,20,0,'two','[1.0]')
                        """, 2));
    }

    private void executeMigration() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : migrationStatements) {
                statement.execute(sql);
            }
        }
    }

    private void executeSqlStatements(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String part : sql.split(";")) {
                if (!part.isBlank()) {
                    statement.execute(part.trim());
                }
            }
        }
    }

    private List<String> parseMigration(String script) {
        String startMarker = "DELIMITER $$";
        String endMarker = "DELIMITER ;";
        int bodyStart = script.indexOf(startMarker);
        int bodyEnd = script.indexOf(endMarker, bodyStart + startMarker.length());
        if (bodyStart < 0 || bodyEnd < 0) {
            throw new IllegalArgumentException("Migration delimiter markers are missing");
        }

        List<String> statements = new ArrayList<>();
        String body = script.substring(bodyStart + startMarker.length(), bodyEnd);
        for (String statement : body.split("\\$\\$")) {
            if (!statement.isBlank()) {
                statements.add(statement.trim());
            }
        }
        String tail = script.substring(bodyEnd + endMarker.length());
        for (String statement : tail.split(";")) {
            if (!statement.isBlank()) {
                statements.add(statement.trim());
            }
        }
        return List.copyOf(statements);
    }

    private void assertAllTargetStructuresPresent() {
        assertTrue(exactIndexCount("knowledge_documents", 0, "id,user_id") >= 1);
        assertTrue(exactIndexCount("knowledge_chunks", 0, "document_id,chunk_index") >= 1);
        assertTrue(exactIndexCount("knowledge_chunks", 1, "document_id,user_id") >= 1);
        Integer foreignKeys = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM (
                    SELECT k.CONSTRAINT_NAME
                    FROM information_schema.KEY_COLUMN_USAGE k
                    JOIN information_schema.REFERENTIAL_CONSTRAINTS r
                      ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA
                     AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME
                     AND r.TABLE_NAME=k.TABLE_NAME
                    WHERE k.CONSTRAINT_SCHEMA=DATABASE()
                      AND k.TABLE_NAME='knowledge_chunks'
                    GROUP BY k.CONSTRAINT_NAME
                    HAVING COUNT(*)=2
                       AND GROUP_CONCAT(k.COLUMN_NAME ORDER BY k.ORDINAL_POSITION SEPARATOR ',')
                           ='document_id,user_id'
                       AND GROUP_CONCAT(k.REFERENCED_COLUMN_NAME
                                        ORDER BY k.ORDINAL_POSITION SEPARATOR ',')='id,user_id'
                       AND MAX(k.REFERENCED_TABLE_NAME)='knowledge_documents'
                       AND MAX(r.DELETE_RULE)='CASCADE'
                ) exact_fk
                """, Integer.class);
        assertTrue(foreignKeys != null && foreignKeys >= 1);
    }

    private int exactIndexCount(String table, int nonUnique, String columns) {
        Integer result = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM (
                    SELECT INDEX_NAME
                    FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?
                    GROUP BY INDEX_NAME
                    HAVING MIN(NON_UNIQUE)=?
                       AND MAX(NON_UNIQUE)=?
                       AND COUNT(*)=2
                       AND GROUP_CONCAT(COALESCE(COLUMN_NAME,'<expression>')
                                        ORDER BY SEQ_IN_INDEX SEPARATOR ',')=?
                       AND SUM(CASE WHEN SUB_PART IS NOT NULL THEN 1 ELSE 0 END)=0
                       AND SUM(CASE WHEN COLUMN_NAME IS NULL OR EXPRESSION IS NOT NULL
                                    THEN 1 ELSE 0 END)=0
                ) exact_index
                """, Integer.class, table, nonUnique, nonUnique, columns);
        return result == null ? 0 : result;
    }

    private String schemaSignature() {
        return jdbc.queryForList("""
                SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,SUB_PART,EXPRESSION
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE()
                  AND TABLE_NAME IN ('knowledge_documents','knowledge_chunks')
                ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX
                """).toString()
                + jdbc.queryForList("""
                SELECT CONSTRAINT_NAME,TABLE_NAME,REFERENCED_TABLE_NAME,DELETE_RULE
                FROM information_schema.REFERENTIAL_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA=DATABASE()
                ORDER BY CONSTRAINT_NAME
                """);
    }

    private void insertDocument(long id, long userId, String status, int chunkCount) {
        jdbc.update("""
                INSERT INTO knowledge_documents(id,user_id,status,chunk_count)
                VALUES(?,?,?,?)
                """, id, userId, status, chunkCount);
    }

    private void insertChunk(long id, long documentId, long userId, int index, String content) {
        jdbc.update("""
                INSERT INTO knowledge_chunks
                  (id,document_id,user_id,chunk_index,content,embedding)
                VALUES(?,?,?,?,?,'[1.0]')
                """, id, documentId, userId, index, content);
    }

    private int count(String table) {
        Integer result = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return result == null ? 0 : result;
    }

    private String readSql(String filename) throws IOException {
        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        Path path = workingDirectory.resolve("docs/sql").resolve(filename);
        if (!Files.exists(path)) {
            path = workingDirectory.resolve("../docs/sql").resolve(filename).normalize();
        }
        return Files.readString(path);
    }

    private String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property: " + name);
        }
        return value;
    }
}
