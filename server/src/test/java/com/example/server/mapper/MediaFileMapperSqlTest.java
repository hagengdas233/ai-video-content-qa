package com.example.server.mapper;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaFileMapperSqlTest {

    @Test
    void queueAnalysisUsesExactStatusAndNullSafeRequestIdCas() throws Exception {
        String sql = updateSql("queueAnalysis", Long.class, String.class,
                com.example.server.entity.AnalysisMode.class, String.class,
                com.example.server.entity.AnalysisStatus.class, String.class);

        assertTrue(sql.contains("analysis_mode = #{analysisMode"));
        assertTrue(sql.contains("analysis_status = #{expectedAnalysisStatus"));
        assertTrue(sql.contains("expectedAnalysisStatus") && sql.contains("analysis_status IS NULL"));
        assertTrue(sql.contains("analysis_request_id <=> #{expectedAnalysisRequestId"));
        assertFalse(sql.contains("NOT IN"));
        assertFalse(sql.contains("result_request_id"));
        assertFalse(sql.contains("result_mode"));
        assertFalse(sql.contains("result_goal"));
        assertFalse(sql.contains("result_finished_at"));
        assertFalse(sql.contains("ai_summary"));
        assertFalse(sql.contains("transcript_text"));
    }

    @Test
    void successAtomicallyReplacesResultAndItsAttributionForCurrentRequest() throws Exception {
        String sql = updateSql("markAnalysisSuccess", Long.class, String.class,
                com.example.server.entity.AnalysisMode.class, String.class,
                String.class, String.class, java.time.LocalDateTime.class);

        assertTrue(sql.contains("ai_summary = #{aiSummary"));
        assertTrue(sql.contains("transcript_text = #{transcriptText"));
        assertTrue(sql.contains("result_request_id = #{analysisRequestId}"));
        assertTrue(sql.contains("result_mode = #{analysisMode"));
        assertTrue(sql.contains("result_goal = #{analysisGoal"));
        assertTrue(sql.contains("result_finished_at = #{finishedAt}"));
        assertTrue(sql.contains("analysis_status = 'SUCCESS'"));
        assertTrue(sql.contains("analysis_request_id = #{analysisRequestId}"));
        assertTrue(sql.contains("analysis_status IN ('QUEUED', 'RUNNING')"));
    }

    @Test
    void submitFailureOnlyTransitionsQueuedState() throws Exception {
        String sql = updateSql("markSubmitFailed", Long.class, String.class, String.class,
                java.time.LocalDateTime.class);

        assertTrue(sql.contains("analysis_request_id = #{analysisRequestId}"));
        assertTrue(sql.contains("analysis_status = 'QUEUED'"));
        assertFalse(sql.contains("RUNNING"));
        assertFalse(sql.contains("ai_summary"));
        assertFalse(sql.contains("transcript_text"));
        assertFalse(sql.contains("result_request_id"));
        assertFalse(sql.contains("result_mode"));
        assertFalse(sql.contains("result_goal"));
        assertFalse(sql.contains("result_finished_at"));
    }

    @Test
    void executionFailureOnlyTransitionsRunningState() throws Exception {
        String sql = updateSql("markExecutionFailed", Long.class, String.class, String.class,
                java.time.LocalDateTime.class);

        assertTrue(sql.contains("analysis_request_id = #{analysisRequestId}"));
        assertTrue(sql.contains("analysis_status = 'RUNNING'"));
        assertFalse(sql.contains("QUEUED"));
        assertFalse(sql.contains("ai_summary"));
        assertFalse(sql.contains("transcript_text"));
        assertFalse(sql.contains("result_request_id"));
        assertFalse(sql.contains("result_mode"));
        assertFalse(sql.contains("result_goal"));
        assertFalse(sql.contains("result_finished_at"));
    }

    private String updateSql(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = MediaFileMapper.class.getMethod(methodName, parameterTypes);
        Update update = method.getAnnotation(Update.class);
        return String.join(" ", update.value()).replaceAll("\\s+", " ");
    }
}
