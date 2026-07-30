package com.example.server.dto;

import java.util.List;
import java.util.Objects;

public record ContextSelectionResult(Status status, VideoContext context) {

    public ContextSelectionResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(context, "context must not be null");
    }

    public static ContextSelectionResult matched(VideoContext context) {
        return new ContextSelectionResult(Status.MATCHED, context);
    }

    public static ContextSelectionResult noMatch(VideoContext context) {
        VideoContext emptyContext = new VideoContext(
                context.source(),
                context.analysisMode(),
                context.userGoal(),
                List.of()
        );
        return new ContextSelectionResult(Status.NO_MATCH, emptyContext);
    }

    public boolean isMatched() {
        return status == Status.MATCHED;
    }

    public enum Status {
        MATCHED,
        NO_MATCH
    }
}
