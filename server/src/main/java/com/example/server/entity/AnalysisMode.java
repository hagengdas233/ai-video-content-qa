package com.example.server.entity;

import java.util.Locale;

public enum AnalysisMode {
    FULL,
    GOAL;

    public static final String FULL_INTERNAL_GOAL =
            "Summarize the complete video in chronological order and generate a structured analysis report";

    public static AnalysisMode fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return FULL;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode must be FULL or GOAL");
        }
    }
}
