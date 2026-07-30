package com.example.server.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "ai.video.goal-relevance")
public class VideoGoalRelevanceProperties {

    @DecimalMin(value = "0.0", inclusive = false)
    @DecimalMax("1.0")
    private double longThreshold = 0.50;

    @DecimalMin(value = "0.0", inclusive = false)
    @DecimalMax("1.0")
    private double shortThreshold = 0.55;

    @Min(1)
    private int topK = 3;

    public double getLongThreshold() {
        return longThreshold;
    }

    public void setLongThreshold(double longThreshold) {
        this.longThreshold = longThreshold;
    }

    public double getShortThreshold() {
        return shortThreshold;
    }

    public void setShortThreshold(double shortThreshold) {
        this.shortThreshold = shortThreshold;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }
}
