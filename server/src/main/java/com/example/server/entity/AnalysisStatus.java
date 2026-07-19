package com.example.server.entity;

import java.util.Set;

public enum AnalysisStatus {
    NOT_STARTED,
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED;

    private static final Set<String> LEGACY_QUEUE_PLACEHOLDERS = Set.of(
            "[MQ] Analysis task queued",
            "[MQ] 已进入消息队列，等待调度...");
    private static final String LEGACY_FAILURE_PREFIX = "❌ 分析失败:";

    public boolean isInProgress() {
        return this == QUEUED || this == RUNNING;
    }

    public static AnalysisStatus effective(AnalysisStatus persistedStatus, String aiSummary) {
        if (persistedStatus != null && persistedStatus != NOT_STARTED) {
            return persistedStatus;
        }
        if (isLegacyFailureSummary(aiSummary)) {
            return FAILED;
        }
        if (isLegacySuccessfulSummary(aiSummary)) {
            return SUCCESS;
        }
        return persistedStatus == null ? NOT_STARTED : persistedStatus;
    }

    public static boolean isLegacySuccessfulSummary(String aiSummary) {
        if (aiSummary == null || aiSummary.isBlank()) {
            return false;
        }
        return !isLegacyPlaceholder(aiSummary);
    }

    public static boolean isLegacyPlaceholder(String aiSummary) {
        if (aiSummary == null || aiSummary.isBlank()) {
            return false;
        }
        String value = aiSummary.trim();
        return LEGACY_QUEUE_PLACEHOLDERS.contains(value)
                || value.startsWith(LEGACY_FAILURE_PREFIX);
    }

    public static boolean isLegacyFailureSummary(String aiSummary) {
        return aiSummary != null && aiSummary.trim().startsWith(LEGACY_FAILURE_PREFIX);
    }
}
