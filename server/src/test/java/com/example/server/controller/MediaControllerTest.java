package com.example.server.controller;

import com.example.server.auth.UserContext;
import com.example.server.entity.AnalysisMode;
import com.example.server.entity.MediaFile;
import com.example.server.service.MediaAnalysisTaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void mediaListEntitySerializesSuccessfulResultMetadata() throws Exception {
        MediaFile file = new MediaFile();
        file.setId(1L);
        file.setResultRequestId("11111111-1111-4111-8111-111111111111");
        file.setResultMode(AnalysisMode.GOAL);
        file.setResultGoal("question A");
        file.setResultFinishedAt(LocalDateTime.of(2026, 8, 2, 10, 0));

        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(List.of(file)).get(0);

        assertEquals(file.getResultRequestId(), json.get("resultRequestId").asText());
        assertEquals("GOAL", json.get("resultMode").asText());
        assertEquals("question A", json.get("resultGoal").asText());
        assertTrue(json.hasNonNull("resultFinishedAt"));
    }

    @Test
    void analysisResponseSerializesSuccessfulResultMetadata() throws Exception {
        Map<String, Object> serviceResult = new HashMap<>();
        serviceResult.put("status", "REUSED");
        serviceResult.put("resultRequestId", "11111111-1111-4111-8111-111111111111");
        serviceResult.put("resultMode", AnalysisMode.GOAL);
        serviceResult.put("resultGoal", "question A");
        serviceResult.put("resultFinishedAt", LocalDateTime.of(2026, 8, 2, 10, 0));
        when(mediaAnalysisTaskService.submitAnalysis(1L, 7L, "GOAL", "question A", false))
                .thenReturn(serviceResult);

        ResponseEntity<?> response = controller.analyze(
                1L, false, Map.of("mode", "GOAL", "goal", "question A"));
        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(response.getBody());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(serviceResult.get("resultRequestId"), json.get("resultRequestId").asText());
        assertEquals("GOAL", json.get("resultMode").asText());
        assertEquals("question A", json.get("resultGoal").asText());
        assertTrue(json.hasNonNull("resultFinishedAt"));
    }
}
