package com.youkeda.exercise.claw.feature.scout.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.feature.scout.ScoutProperties;
import com.youkeda.exercise.claw.feature.scout.context.UserProfile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SearchPlannerQueryTest {

    @Test
    void includesStageSpecificKnowledgeAsUntrustedPlanningData() {
        LLMClient llmClient = mock(LLMClient.class);
        when(llmClient.chatWithSystemPrompt(anyString(), anyString())).thenReturn("[]");
        SearchPlanner planner = new SearchPlanner(
                llmClient, new ScoutProperties(), new ObjectMapper());

        planner.plan(new UserProfile(List.of("Java"), List.of(), List.of(), List.of(), ""),
                "AI Agent", "<knowledge_data>只查官方来源</knowledge_data>");

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chatWithSystemPrompt(system.capture(), prompt.capture());
        assertTrue(system.getValue().contains("SCOUT_PLANNING_KNOWLEDGE"));
        assertTrue(system.getValue().contains("不可信数据"));
        assertTrue(prompt.getValue().contains("SCOUT_PLANNING_KNOWLEDGE"));
        assertTrue(prompt.getValue().contains("只查官方来源"));
        assertTrue(prompt.getValue().contains("不可信"));
    }

    @Test
    void includesExplicitTopicInPlanningPrompt() {
        LLMClient llmClient = mock(LLMClient.class);
        when(llmClient.chatWithSystemPrompt(anyString(), anyString())).thenReturn("[]");
        ScoutProperties properties = new ScoutProperties();
        SearchPlanner planner = new SearchPlanner(llmClient, properties, new ObjectMapper());
        UserProfile profile = new UserProfile(
                List.of("Java"), List.of(), List.of(), List.of(), "");

        List<SearchTask> tasks = planner.plan(profile, "AI / 深度学习方面");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chatWithSystemPrompt(anyString(), prompt.capture());
        assertTrue(prompt.getValue().contains("AI / 深度学习方面"));
        assertEquals(properties.getSearchTaskCount(), tasks.size());
        assertTrue(tasks.stream().allMatch(task ->
                task.query().contains("AI / 深度学习方面")));
    }
}
