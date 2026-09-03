package com.youkeda.exercise.claw.feature.scout.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.agent.memory.Message;
import com.youkeda.exercise.claw.agent.memory.MessageRole;
import com.youkeda.exercise.claw.agent.memory.SqliteContextStore;
import com.youkeda.exercise.claw.agent.memory.longterm.LongTermMemoryService;
import com.youkeda.exercise.claw.agent.memory.longterm.MemoryCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户行为分析器
 *
 * 从对话历史中提取用户新的兴趣点，自动更新长期记忆
 */
@Service
public class UserBehaviorAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(UserBehaviorAnalyzer.class);

    private static final String ANALYSIS_PROMPT = """
            分析以下用户的对话历史，提取用户新表现出的兴趣、关注领域、技术偏好。

            要求：
            1. 只提取用户主动表达的兴趣（不是助手推荐的）
            2. 每个兴趣用简短的中文描述（不超过20字）
            3. 最多提取 5 条
            4. 不要与已有兴趣重复

            返回 JSON 数组：
            ["兴趣1", "兴趣2"]

            如果没有新兴趣，返回空数组：[]
            """;

    private final SqliteContextStore contextStore;
    private final LongTermMemoryService memoryService;
    private final LLMClient llmClient;
    private final ObjectMapper objectMapper;

    public UserBehaviorAnalyzer(SqliteContextStore contextStore,
                                 LongTermMemoryService memoryService,
                                 LLMClient llmClient,
                                 ObjectMapper objectMapper) {
        this.contextStore = contextStore;
        this.memoryService = memoryService;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 分析用户近期对话，提取新兴趣并存入长期记忆
     *
     * @return 新增的兴趣数量
     */
    public int analyzeAndStore() {
        // 1. 获取最近 20 条对话
        List<Message> history = contextStore.getHistory(20);
        if (history.isEmpty()) {
            log.debug("无对话历史，跳过行为分析");
            return 0;
        }

        // 2. 格式化对话文本
        String conversationText = formatConversation(history);

        // 3. LLM 提取新兴趣
        List<String> newInterests = extractInterests(conversationText);
        if (newInterests.isEmpty()) {
            log.debug("未发现新兴趣");
            return 0;
        }

        // 4. 存入长期记忆
        int saved = 0;
        for (String interest : newInterests) {
            try {
                boolean success = memoryService.saveManual(
                        MemoryCategory.PREFERENCE, interest);
                if (success) saved++;
            } catch (Exception e) {
                log.error("保存兴趣失败 | interest={}", interest, e);
            }
        }

        log.info("用户行为分析完成 | newInterests={} | saved={}", newInterests.size(), saved);
        return saved;
    }

    /**
     * 格式化对话历史
     */
    private String formatConversation(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            if (msg.role() == null || msg.content() == null) continue;
            // 只关注用户消息
            if (msg.role() == MessageRole.USER) {
                sb.append("用户：").append(msg.content()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * LLM 提取新兴趣
     */
    private List<String> extractInterests(String conversationText) {
        try {
            String prompt = "以下是用户的近期对话：\n\n" + conversationText;
            String result = llmClient.chatWithSystemPrompt(ANALYSIS_PROMPT, prompt);
            if (result == null || result.isBlank()) return List.of();

            return parseInterests(result);
        } catch (Exception e) {
            log.error("兴趣提取失败", e);
            return List.of();
        }
    }

    /**
     * 解析 LLM 返回的兴趣列表
     */
    private List<String> parseInterests(String json) {
        List<String> interests = new ArrayList<>();
        try {
            String jsonStr = extractJson(json);
            JsonNode arr = objectMapper.readTree(jsonStr);
            if (!arr.isArray()) return interests;

            for (JsonNode item : arr) {
                String text = item.asText("").trim();
                if (!text.isEmpty() && text.length() <= 20) {
                    interests.add(text);
                }
            }
        } catch (Exception e) {
            log.error("兴趣 JSON 解析失败 | json={}", json, e);
        }
        return interests;
    }

    private String extractJson(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('[');
            int end = trimmed.lastIndexOf(']');
            if (start >= 0 && end > start) {
                return trimmed.substring(start, end + 1);
            }
        }
        return trimmed;
    }
}
