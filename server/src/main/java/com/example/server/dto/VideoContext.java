package com.example.server.dto;

import com.example.server.entity.AnalysisMode;

import java.util.List;

/**
 * 将视频中的语音和画面信息按时间轴整理成 Agent 可消费的统一上下文。
 */
public record VideoContext(
        String source,
        AnalysisMode analysisMode,
        String userGoal,
        List<VideoSegment> segments
) {
    public record VideoSegment(
            long startMs,
            long endMs,
            String transcript,
            String contentSource,
            List<String> ocrTexts,
            List<String> evidenceFrames
    ) {
        public VideoSegment(long startMs,
                            long endMs,
                            String transcript,
                            List<String> ocrTexts,
                            List<String> evidenceFrames) {
            this(startMs, endMs, transcript, "ASR", ocrTexts, evidenceFrames);
        }
    }

    public String transcriptText() {
        return segments.stream()
                .map(VideoSegment::transcript)
                .filter(text -> text != null && !text.isBlank())
                .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
    }
}
