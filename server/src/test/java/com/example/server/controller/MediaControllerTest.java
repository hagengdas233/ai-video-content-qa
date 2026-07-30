package com.example.server.controller;

import com.example.server.auth.UserContext;
import com.example.server.service.MediaAnalysisTaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaControllerTest {

    @Mock
    private MediaAnalysisTaskService mediaAnalysisTaskService;

    private MediaController controller;

    @BeforeEach
    void setUp() {
        controller = new MediaController();
        ReflectionTestUtils.setField(
                controller, "mediaAnalysisTaskService", mediaAnalysisTaskService);
        UserContext.setUserId(7L);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void goalModeWithBlankGoalReturnsBadRequest() {
        when(mediaAnalysisTaskService.submitAnalysis(1L, 7L, "GOAL", "   ", false))
                .thenThrow(new IllegalArgumentException(
                        "goal is required when mode is GOAL"));

        ResponseEntity<?> response = controller.analyze(
                1L, false, Map.of("mode", "GOAL", "goal", "   "));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("goal is required when mode is GOAL", response.getBody());
    }
}
