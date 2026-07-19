package com.example.server.dto;

public record AiAnalysisOutput(String transcriptText, String aiSummary) {
    public AiAnalysisOutput {
        if (aiSummary == null || aiSummary.isBlank()) {
            throw new IllegalArgumentException("AI analysis result must not be blank");
        }
    }
}
