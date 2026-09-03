package com.youkeda.exercise.claw.feature.scout.notifier;

import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.feature.scout.judge.Recommendation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 将本轮推荐综合成一条独立的站内通知。
 */
@Service
public class RecommendationSummaryService {

    private static final Logger log =
            LoggerFactory.getLogger(RecommendationSummaryService.class);
    private static final int FIRST_SUMMARY_TOKENS = 900;
    private static final int RETRY_SUMMARY_TOKENS = 1400;

    private static final String SYSTEM_PROMPT = """
            你是信息猎手的总结助手。请综合本轮已经筛选出的资讯，生成一条简洁中文总结。

            要求：
            1. 归纳 2-4 个共同趋势或值得关注的方向，不要逐条复述
            2. 结合推荐理由给出明确的优先阅读建议
            3. 只能使用输入中的事实，不得补充或猜测外部信息
            4. 控制在 180-300 个中文字
            5. 使用适合通知中心阅读的短段落或短列表
            6. 不要重复“由 AI 信息猎手生成”等尾注
            """;

    private final LLMClient llmClient;

    public RecommendationSummaryService(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    public String summarize(List<Recommendation> recommendations) {
        if (recommendations == null || recommendations.isEmpty()) {
            return "";
        }
        try {
            String result = llmClient.chatWithSystemPrompt(
                    SYSTEM_PROMPT, buildPrompt(recommendations), FIRST_SUMMARY_TOKENS);
            if (result == null || result.isBlank()) {
                log.warn("信息猎手综合总结首次返回空，增加输出预算重试");
                result = llmClient.chatWithSystemPrompt(
                        SYSTEM_PROMPT,
                        buildPrompt(recommendations)
                                + "\n\n请直接输出最终总结，不要输出分析过程。",
                        RETRY_SUMMARY_TOKENS);
            }
            if (result != null && !result.isBlank()) {
                String trimmed = result.trim();
                return trimmed.startsWith("📌 信息猎手总结")
                        ? trimmed
                        : "📌 信息猎手总结\n\n" + trimmed;
            }
        } catch (Exception e) {
            log.warn("信息猎手综合总结生成失败，使用本地降级总结", e);
        }
        return fallbackSummary(recommendations);
    }

    private String buildPrompt(List<Recommendation> recommendations) {
        StringBuilder sb = new StringBuilder();
        sb.append("本轮推荐共 ").append(recommendations.size()).append(" 条：\n\n");
        for (int i = 0; i < recommendations.size(); i++) {
            Recommendation recommendation = recommendations.get(i);
            sb.append(i + 1).append(". ")
                    .append(recommendation.title()).append("\n")
                    .append("摘要：").append(truncate(recommendation.summary(), 120)).append("\n")
                    .append("推荐理由：").append(truncate(recommendation.reason(), 60)).append("\n")
                    .append("等级：")
                    .append(recommendation.tier() == Recommendation.Tier.STRONG
                            ? "强推荐" : "补充发现")
                    .append("\n\n");
        }
        sb.append("请给出跨条目的综合结论和阅读优先级。");
        return sb.toString();
    }

    private String fallbackSummary(List<Recommendation> recommendations) {
        long strongCount = recommendations.stream()
                .filter(rec -> rec.tier() == Recommendation.Tier.STRONG)
                .count();
        String priorities = recommendations.stream()
                .limit(3)
                .map(Recommendation::title)
                .reduce((left, right) -> left + "、" + right)
                .orElse("");

        StringBuilder sb = new StringBuilder("📌 信息猎手总结\n\n");
        sb.append("本轮共筛选出 ").append(recommendations.size()).append(" 条信息");
        if (strongCount > 0) {
            sb.append("，其中 ").append(strongCount).append(" 条为强推荐");
        }
        sb.append("。\n");
        if (!priorities.isBlank()) {
            sb.append("建议优先关注：").append(priorities).append("。\n");
        }
        sb.append("其余内容可作为扩展视野的快速阅读材料。");
        return sb.toString();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() > maxLength
                ? value.substring(0, maxLength) + "..."
                : value;
    }
}
