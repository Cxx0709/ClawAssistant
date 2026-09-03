package com.youkeda.exercise.claw.feature.scout.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.feature.scout.ScoutProperties;
import com.youkeda.exercise.claw.feature.scout.context.UserProfile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Year;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SearchPlannerSourcePolicyTest {

    @Test
    void preservesJobAndCompetitionPlanningWithoutChangingCollectorPolicy() {
        LLMClient llmClient = mock(LLMClient.class);
        String currentYear = String.valueOf(Year.now().getValue());
        when(llmClient.chatWithSystemPrompt(anyString(), anyString())).thenReturn(("""
                [
                  {"query":"AI Agent latest news %s","category":"JOB","reason":"jobs","priority":3},
                  {"query":"AI competition latest","category":"COMPETITION","reason":"contest","priority":3},
                  {"query":"AI framework competition","category":"COMPETITION","reason":"contest","priority":5}
                ]
                """).formatted(currentYear));
        SearchPlanner planner = new SearchPlanner(
                llmClient, new ScoutProperties(), new ObjectMapper());
        UserProfile profile = new UserProfile(
                List.of("AI"), List.of(), List.of(), List.of(), "");

        List<SearchTask> tasks = planner.plan(profile);

        assertEquals(8, tasks.size());
        assertTrue(tasks.stream().anyMatch(task -> SearchTask.JOB.equals(task.category())));
        assertTrue(tasks.stream().anyMatch(task -> SearchTask.COMPETITION.equals(task.category())));
        assertEquals(5, tasks.stream().filter(task ->
                !SearchTask.JOB.equals(task.category())
                        && !SearchTask.COMPETITION.equals(task.category())).count());
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chatWithSystemPrompt(systemPrompt.capture(), anyString());
        assertTrue(systemPrompt.getValue().contains("NEWS、BLOG、GITHUB、JOB、COMPETITION"));
    }
}
