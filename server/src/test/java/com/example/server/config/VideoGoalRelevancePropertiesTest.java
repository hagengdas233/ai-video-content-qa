package com.example.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class VideoGoalRelevancePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void defaultsMatchConfiguredInitialValues() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            VideoGoalRelevanceProperties properties =
                    context.getBean(VideoGoalRelevanceProperties.class);
            assertThat(properties.getLongThreshold()).isEqualTo(0.50);
            assertThat(properties.getShortThreshold()).isEqualTo(0.55);
            assertThat(properties.getTopK()).isEqualTo(3);
        });
    }

    @Test
    void propertyOverridesAreBound() {
        contextRunner
                .withPropertyValues(
                        "ai.video.goal-relevance.long-threshold=0.60",
                        "ai.video.goal-relevance.short-threshold=0.65",
                        "ai.video.goal-relevance.top-k=5")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    VideoGoalRelevanceProperties properties =
                            context.getBean(VideoGoalRelevanceProperties.class);
                    assertThat(properties.getLongThreshold()).isEqualTo(0.60);
                    assertThat(properties.getShortThreshold()).isEqualTo(0.65);
                    assertThat(properties.getTopK()).isEqualTo(5);
                });
    }

    @Test
    void environmentStyleOverridesAreBound() {
        contextRunner
                .withInitializer(context -> TestPropertyValues.of(
                                "AI_VIDEO_GOAL_RELEVANCE_LONG_THRESHOLD=0.70",
                                "AI_VIDEO_GOAL_RELEVANCE_SHORT_THRESHOLD=0.75",
                                "AI_VIDEO_GOAL_RELEVANCE_TOP_K=7")
                        .applyTo(context.getEnvironment(),
                                TestPropertyValues.Type.SYSTEM_ENVIRONMENT))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    VideoGoalRelevanceProperties properties =
                            context.getBean(VideoGoalRelevanceProperties.class);
                    assertThat(properties.getLongThreshold()).isEqualTo(0.70);
                    assertThat(properties.getShortThreshold()).isEqualTo(0.75);
                    assertThat(properties.getTopK()).isEqualTo(7);
                });
    }

    @Test
    void zeroLongThresholdFailsValidation() {
        contextRunner
                .withPropertyValues("ai.video.goal-relevance.long-threshold=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class);
                });
    }

    @Test
    void negativeShortThresholdFailsValidation() {
        contextRunner
                .withPropertyValues("ai.video.goal-relevance.short-threshold=-0.1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class);
                });
    }

    @Test
    void thresholdAboveOneFailsValidation() {
        contextRunner
                .withPropertyValues("ai.video.goal-relevance.long-threshold=1.01")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class);
                });
    }

    @Test
    void positiveLowerBoundaryAndOneAreBound() {
        contextRunner
                .withPropertyValues(
                        "ai.video.goal-relevance.long-threshold=0.01",
                        "ai.video.goal-relevance.short-threshold=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    VideoGoalRelevanceProperties properties =
                            context.getBean(VideoGoalRelevanceProperties.class);
                    assertThat(properties.getLongThreshold()).isEqualTo(0.01);
                    assertThat(properties.getShortThreshold()).isEqualTo(1.0);
                });
    }

    @Test
    void nonPositiveTopKFailsValidation() {
        contextRunner
                .withPropertyValues("ai.video.goal-relevance.top-k=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(VideoGoalRelevanceProperties.class)
    static class TestConfiguration {
    }
}
