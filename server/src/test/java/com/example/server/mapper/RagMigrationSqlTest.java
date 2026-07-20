package com.example.server.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagMigrationSqlTest {

    private static final Pattern MUTATING_STATEMENT = Pattern.compile(
            "(?im)^\\s*(delete|update|insert|alter|create|drop|call|set|truncate|replace)\\b");

    @Test
    void precheckContainsOnlyReadOnlyStatementsAndAllRequiredInspections() throws IOException {
        String sql = readSql("precheck_rag_safety_consistency.sql");
        String withoutComments = sql.replaceAll("(?m)^\\s*--.*$", "");

        assertFalse(MUTATING_STATEMENT.matcher(withoutComments).find());
        assertTrue(sql.contains("orphan_chunks"));
        assertTrue(sql.contains("chunk_document_user_mismatch"));
        assertTrue(sql.contains("FAILED") && sql.contains("PROCESSING"));
        assertTrue(sql.contains("ready_duplicate_document_chunk_index"));
        assertTrue(sql.contains("information_schema.TABLES"));
        assertTrue(sql.contains("COLUMN_TYPE"));
        assertTrue(sql.contains("information_schema.STATISTICS"));
        assertTrue(sql.contains("information_schema.REFERENTIAL_CONSTRAINTS"));
    }

    @Test
    void migrationHardStopsBeforeCleanupAndRechecksBeforeConstraintApplication() throws IOException {
        String sql = readSql("migrate_rag_safety_consistency.sql");
        int firstGuard = sql.indexOf("CALL rag_safety_guard(FALSE)");
        int metadataPlan = sql.indexOf("CALL rag_safety_plan_constraints()", firstGuard);
        int lifecycleDelete = sql.indexOf("DELETE c", firstGuard);
        int secondGuard = sql.indexOf("CALL rag_safety_guard(TRUE)", lifecycleDelete);
        int applyConstraints = sql.indexOf("CALL rag_safety_apply_constraints()", secondGuard);

        assertTrue(sql.contains("SIGNAL SQLSTATE '45000'"));
        assertTrue(firstGuard >= 0 && metadataPlan > firstGuard && lifecycleDelete > metadataPlan);
        assertTrue(secondGuard > lifecycleDelete && applyConstraints > secondGuard);
        assertTrue(sql.contains("WHERE d.status IN ('FAILED', 'PROCESSING')"));
        assertTrue(sql.contains("ON DELETE CASCADE"));
        assertTrue(sql.contains("MySQL DDL implicitly commits"));
        assertFalse(sql.contains("HAVING NON_UNIQUE = 0"));
        assertTrue(sql.contains("HAVING MIN(NON_UNIQUE) = 0"));
        assertTrue(sql.contains("COUNT(*) = 2"));
        assertTrue(sql.contains("SUB_PART IS NOT NULL"));
        assertTrue(sql.contains("EXPRESSION IS NOT NULL"));
        assertTrue(sql.contains("SEPARATOR ','"));
    }

    private String readSql(String filename) throws IOException {
        Path workingDirectory = Path.of(System.getProperty("user.dir"));
        Path path = workingDirectory.resolve("docs/sql").resolve(filename);
        if (!Files.exists(path)) {
            path = workingDirectory.resolve("../docs/sql").resolve(filename).normalize();
        }
        return Files.readString(path);
    }
}
