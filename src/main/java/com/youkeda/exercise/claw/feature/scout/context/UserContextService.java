package com.youkeda.exercise.claw.feature.scout.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.agent.memory.longterm.LongTermMemoryService;
import com.youkeda.exercise.claw.agent.memory.longterm.MemoryItem;
import com.youkeda.exercise.claw.feature.goal.GrowthGoal;
import com.youkeda.exercise.claw.feature.goal.GrowthGoalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户画像服务
 *
 * 从长期记忆中提取用户兴趣、项目、技术栈、目标
 */
@Service
public class UserContextService {

    private static final Logger log = LoggerFactory.getLogger(UserContextService.class);

    private static final String SYSTEM_PROMPT = """
            你是用户画像分析专家。根据用户的长期记忆，提取结构化画像信息。

            提取维度：
            - interests: 用户感兴趣的领域（如 AI、Java、创业等）
            - currentProjects: 用户当前在做的项目
            - techStack: 用户使用的技术栈
            - goals: 用户的学习或职业目标

            要求：
            1. 只从记忆中提取，不要编造
            2. 每个维度最多 5 项
            3. 每项用简短的中文描述
            4. 返回严格的 JSON 格式

            返回格式：
            {
              "interests": ["AI Agent", "Java"],
              "currentProjects": ["Claw Web Assistant"],
              "techStack": ["Spring Boot", "Qdrant"],
              "goals": ["掌握AI Agent开发"]
            }
            """;

    private final LongTermMemoryService memoryService;
    private final LLMClient llmClient;
    private final ObjectMapper objectMapper;
    private final GrowthGoalRepository goalRepository;

    public UserContextService(LongTermMemoryService memoryService,
                              LLMClient llmClient,
                              ObjectMapper objectMapper,
                              GrowthGoalRepository goalRepository) {
        this.memoryService = memoryService;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.goalRepository = goalRepository;
    }

    /**
     * 从长期记忆构建用户画像，同时注入活跃的成长目标
     */
    public UserProfile buildProfile() {
        // 1. 获取用户所有长期记忆
        List<MemoryItem> memories = memoryService.listAll();

        // 2. 获取活跃成长目标
        List<String> activeGoalTexts = new ArrayList<>();
        try {
            List<GrowthGoal> activeGoals = goalRepository.findAllActive();
            for (GrowthGoal g : activeGoals) {
                String text = g.title();
                if (g.successCriteria() != null && !g.successCriteria().isBlank()) {
                    text += "（成功标准：" + g.successCriteria() + "）";
                }
                if (g.deadline() != null) {
                    text += "，截止 " + g.deadline();
                }
                activeGoalTexts.add(text);
            }
            if (!activeGoalTexts.isEmpty()) {
                log.info("注入活跃成长目标 | count={}", activeGoalTexts.size());
            }
        } catch (Exception e) {
            log.warn("读取成长目标失败，跳过注入 | error={}", e.getMessage());
        }

        if (memories.isEmpty() && activeGoalTexts.isEmpty()) {
            log.info("用户无长期记忆和活跃目标，返回空画像");
            return new UserProfile(List.of(), List.of(), List.of(), List.of(), "");
        }

        // 3. 拼接记忆文本
        String memoryText = formatMemories(memories);

        // 4. LLM 提取画像
        try {
            String prompt = "以下是用户的长期记忆：\n\n" + memoryText;
            if (!activeGoalTexts.isEmpty()) {
                prompt += "\n\n用户的活跃成长目标：\n";
                for (String goal : activeGoalTexts) {
                    prompt += "- " + goal + "\n";
                }
                prompt += "\n请将上述活跃目标也纳入 goals 维度。";
            }
            String json = llmClient.chatWithSystemPrompt(SYSTEM_PROMPT, prompt);
            if (json == null || json.isBlank()) {
                log.warn("LLM 返回空，使用默认画像");
                return fallbackProfile(memories, activeGoalTexts);
            }

            return parseProfile(json, activeGoalTexts);
        } catch (Exception e) {
            log.error("用户画像提取失败，使用降级方案", e);
            return fallbackProfile(memories, activeGoalTexts);
        }
    }

    private String formatMemories(List<MemoryItem> memories) {
        StringBuilder sb = new StringBuilder();
        for (MemoryItem m : memories) {
            sb.append("- [").append(m.category()).append("] ");
            if (m.topicKey() != null && !m.topicKey().isBlank()) {
                sb.append(m.topicKey()).append(": ");
            }
            sb.append(m.content()).append("\n");
        }
        return sb.toString();
    }

    private UserProfile parseProfile(String json, List<String> activeGoalTexts) {
        try {
            // 提取 JSON 部分（LLM 可能返回 markdown 包裹的 JSON）
            String jsonStr = extractJson(json);
            JsonNode root = objectMapper.readTree(jsonStr);

            List<String> interests = parseStringArray(root, "interests");
            List<String> projects = parseStringArray(root, "currentProjects");
            List<String> techStack = parseStringArray(root, "techStack");
            List<String> goals = parseStringArray(root, "goals");

            // 合并活跃成长目标（去重）
            for (String goalText : activeGoalTexts) {
                boolean exists = goals.stream().anyMatch(g ->
                        g.contains(goalText) || goalText.contains(g));
                if (!exists) {
                    goals.add(goalText);
                }
            }

            String summary = "兴趣：" + String.join("、", interests)
                    + "；项目：" + String.join("、", projects)
                    + "；技术：" + String.join("、", techStack);

            log.info("用户画像提取成功 | interests={} | projects={} | goals={}",
                    interests.size(), projects.size(), goals.size());

            return new UserProfile(interests, projects, techStack, goals, summary);
        } catch (Exception e) {
            log.error("画像 JSON 解析失败 | json={}", json, e);
            return new UserProfile(List.of(), List.of(), List.of(), activeGoalTexts, "");
        }
    }

    private UserProfile fallbackProfile(List<MemoryItem> memories, List<String> activeGoalTexts) {
        // 降级方案：直接从记忆内容中提取关键词
        List<String> interests = new ArrayList<>();
        for (MemoryItem m : memories) {
            if (m.content().length() < 50) {
                interests.add(m.content());
            }
            if (interests.size() >= 5) break;
        }
        return new UserProfile(interests, List.of(), List.of(), activeGoalTexts, "");
    }

    private List<String> parseStringArray(JsonNode root, String field) {
        List<String> result = new ArrayList<>();
        JsonNode arr = root.get(field);
        if (arr != null && arr.isArray()) {
            for (JsonNode item : arr) {
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    private String extractJson(String text) {
        // 处理 LLM 返回的 markdown 包裹的 JSON
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return trimmed.substring(start, end + 1);
            }
        }
        return trimmed;
    }
}
