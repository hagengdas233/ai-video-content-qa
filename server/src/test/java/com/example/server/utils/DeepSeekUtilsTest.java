package com.example.server.utils;

import com.example.server.dto.AgentState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeepSeekUtilsTest {

    private final DeepSeekUtils utils = new DeepSeekUtils(
            "test-key", "http://localhost", "test-model", new ObjectMapper());

    @Test
    void plannerAcceptsStringTasks() throws Exception {
        AgentState.AgentPlan plan = utils.parsePlanResponse("""
                {"understoodGoal":"goal","tasks":["first","second"]}
                """);

        assertEquals("goal", plan.understoodGoal());
        assertEquals(List.of("first", "second"), plan.tasks());
    }

    @Test
    void plannerFlattensProviderObjectTasksToText() throws Exception {
        AgentState.AgentPlan plan = utils.parsePlanResponse("""
                {
                  "understoodGoal": "goal",
                  "tasks": [
                    {"title": "Inspect risks", "description": "Use timestamp evidence"},
                    {"task": "Summarize conclusions"}
                  ]
                }
                """);

        assertEquals(List.of(
                "Inspect risks - Use timestamp evidence",
                "Summarize conclusions"
        ), plan.tasks());
    }
}
