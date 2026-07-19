package com.example.server.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisStatusTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "正在推进数字化转型……",
            "任务已完成的核心内容……",
            "Analysis task governance and operational results",
            "Queued systems require careful capacity planning",
            "任意未知但非空的摘要"
    })
    void unknownNonBlankSummariesArePreservedAsSuccessfulResults(String summary) {
        assertTrue(AnalysisStatus.isLegacySuccessfulSummary(summary));
        assertFalse(AnalysisStatus.isLegacyPlaceholder(summary));
        assertEquals(AnalysisStatus.SUCCESS,
                AnalysisStatus.effective(AnalysisStatus.NOT_STARTED, summary));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[MQ] Analysis task queued",
            "[MQ] 已进入消息队列，等待调度..."
    })
    void exactHistoricalQueuePlaceholdersAreNotSuccessfulResults(String summary) {
        assertTrue(AnalysisStatus.isLegacyPlaceholder(summary));
        assertFalse(AnalysisStatus.isLegacySuccessfulSummary(summary));
        assertEquals(AnalysisStatus.NOT_STARTED,
                AnalysisStatus.effective(AnalysisStatus.NOT_STARTED, summary));
    }

    @Test
    void exactHistoricalFailureFormatBecomesFailedState() {
        String summary = "❌ 分析失败: provider unavailable";

        assertTrue(AnalysisStatus.isLegacyPlaceholder(summary));
        assertTrue(AnalysisStatus.isLegacyFailureSummary(summary));
        assertFalse(AnalysisStatus.isLegacySuccessfulSummary(summary));
        assertEquals(AnalysisStatus.FAILED,
                AnalysisStatus.effective(AnalysisStatus.NOT_STARTED, summary));
    }

    @Test
    void nearMissOfHistoricalPlaceholderIsPreserved() {
        String summary = "[MQ] Analysis task queued with a discussion of system design";

        assertFalse(AnalysisStatus.isLegacyPlaceholder(summary));
        assertTrue(AnalysisStatus.isLegacySuccessfulSummary(summary));
    }
}
