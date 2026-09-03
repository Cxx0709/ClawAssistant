package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.agent.memory.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebChatControllerHistoryTest {

    @Test
    void exposesOnlyUserAndVisibleAssistantMessages() {
        List<Message> stored = List.of(
                new Message("user", "规划杭州两日游"),
                new Message("assistant", "{\"city\":\"杭州\"}",
                        null, null, null, "call-1", "travel_collect"),
                new Message("tool", "{\"status\":\"SUCCESS\"}",
                        null, null, null, "call-1", null),
                new Message("assistant", "这是最终行程"));

        assertEquals(List.of(
                Map.of("role", "user", "content", "规划杭州两日游"),
                Map.of("role", "assistant", "content", "这是最终行程")),
                WebChatController.toHistoryItems(stored));
    }

    @Test
    void keepsLatestOneHundredVisibleMessages() {
        List<Message> stored = new ArrayList<>();
        for (int i = 0; i < 105; i++) {
            stored.add(new Message("user", "message-" + i));
        }

        List<Map<String, Object>> result = WebChatController.toHistoryItems(stored);

        assertEquals(100, result.size());
        assertEquals("message-5", result.get(0).get("content"));
        assertEquals("message-104", result.get(99).get("content"));
    }
}
