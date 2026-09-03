package com.youkeda.exercise.claw.feature.campus.classifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.feature.campus.fetcher.NoticeContentFetcher;
import com.youkeda.exercise.claw.domain.campus.ExamClassification;
import com.youkeda.exercise.claw.domain.campus.NotificationItem;
import com.youkeda.exercise.claw.domain.campus.NoticeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ExamLLMClassifier {

    private static final Logger log = LoggerFactory.getLogger(ExamLLMClassifier.class);

    private final LLMClient llmClient;
    private final NoticeContentFetcher contentFetcher;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
        你是一个考试通知分类专家。判断通知标题（和可能的正文）属于哪种考试类型。
        类型: FINAL_EXAM(期末考试), CET(四六级), RETAKE(补考/重修),
              MIDTERM(期中考试), COMPUTER_LEVEL(计算机等级考试),
              PUTONGHUA(普通话测试), OTHER_EXAM(其他考试), NON_EXAM(非考试通知)

        必须严格返回JSON格式（不要 markdown 包裹）:
        {"type":"FINAL_EXAM","confidence":0.95,"reason":"标题包含期末考试安排","scoreSource":"LLM"}

        置信度规则：
        - 标题/正文明确写明考试类型 → 0.90 以上
        - 正文暗示但未明确 → 0.60-0.89
        - 无法判断 → 0.50 以下
        """;

    public ExamLLMClassifier(LLMClient llmClient,
                              NoticeContentFetcher contentFetcher,
                              ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.contentFetcher = contentFetcher;
        this.objectMapper = objectMapper;
    }

    public ExamClassification classify(NotificationItem notice) {
        try {
            // 按需下载正文
            if (notice.needsContent()) {
                String content = contentFetcher.fetch(notice.getUrl());
                notice.setContent(content);
            }

            String prompt = "请分类这条通知：\n标题: " + notice.getTitle()
                + "\n正文: " + notice.getContent();

            String response = llmClient.chatWithSystemPrompt(SYSTEM_PROMPT, prompt);
            return parseResult(response);

        } catch (Exception e) {
            log.error("LLM 分类失败 | title={}", notice.getTitle(), e);
            return new ExamClassification(
                NoticeType.UNKNOWN, 0.0, "LLM 调用失败: " + e.getMessage(), "LLM_ERROR");
        }
    }

    private ExamClassification parseResult(String json) {
        try {
            json = json.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```(?:json)?\\s*|\\s*```$", "");
            }
            JsonNode node = objectMapper.readTree(json);
            String typeStr = node.path("type").asText("UNKNOWN").toUpperCase();
            double confidence = node.path("confidence").asDouble(0);
            String reason = node.path("reason").asText("");
            String source = node.path("scoreSource").asText("LLM");

            NoticeType type;
            try {
                type = NoticeType.valueOf(typeStr);
            } catch (IllegalArgumentException e) {
                type = NoticeType.UNKNOWN;
            }

            return new ExamClassification(type, confidence, reason, source);
        } catch (Exception e) {
            log.warn("LLM 分类 JSON 解析失败 | raw={}", json, e);
            return new ExamClassification(
                NoticeType.UNKNOWN, 0.0, "LLM 响应解析失败", "LLM_ERROR");
        }
    }
}
