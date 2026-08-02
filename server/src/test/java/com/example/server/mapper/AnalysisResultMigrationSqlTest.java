package com.example.server.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisResultMigrationSqlTest {

    @Test
    void migrationIsRepeatableAndBackfillsOnlyUnambiguousSuccessfulResults() throws IOException {
        String sql = readSql("migrate_add_analysis_result_metadata.sql")
                .replaceAll("\\s+", " ");

        assertTrue(sql.contains("information_schema.COLUMNS"));
        assertTrue(sql.contains("COLUMN_NAME = 'result_request_id'"));
        assertTrue(sql.contains("COLUMN_NAME = 'result_mode'"));
        assertTrue(sql.contains("COLUMN_NAME = 'result_goal'"));
        assertTrue(sql.contains("COLUMN_NAME = 'result_finished_at'"));
        assertTrue(sql.contains("analysis_status` = 'SUCCESS'"));
        assertTrue(sql.contains("analysis_request_id` REGEXP"));
        assertTrue(sql.contains("analysis_mode` IN ('FULL', 'GOAL')"));
        assertTrue(sql.contains("analysis_goal` IS NOT NULL"));
        assertTrue(sql.contains("result_request_id` IS NULL"));
        assertTrue(sql.contains("result_mode` IS NULL"));
        assertTrue(sql.contains("result_goal` IS NULL"));
        assertTrue(sql.contains("result_finished_at` IS NULL"));
    }

    private String readSql(String filename) throws IOException {
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path path = workingDirectory.resolve("docs/sql").resolve(filename);
        if (!Files.exists(path)) {
            path = workingDirectory.resolve("../docs/sql").resolve(filename).normalize();
        }
        return Files.readString(path);
    }
}
