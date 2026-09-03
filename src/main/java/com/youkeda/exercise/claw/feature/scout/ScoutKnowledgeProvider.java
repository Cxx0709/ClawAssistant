package com.youkeda.exercise.claw.feature.scout;

import com.youkeda.exercise.claw.ai.retrieval.SkillKnowledgeService;
import org.springframework.stereotype.Component;

/** Produces stage-specific Scout knowledge without coupling Scout internals to Qdrant. */
@Component
public class ScoutKnowledgeProvider {

    private static final String SKILL_NAME = "information-scout";
    private static final String PROFILE_TOPIC = "用户画像相关的持续信息发现";

    private final SkillKnowledgeService knowledgeService;

    public ScoutKnowledgeProvider(SkillKnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    public ScoutExecutionContext forExplicitQuery(String explicitQuery) {
        String normalized = explicitQuery == null ? "" : explicitQuery.trim();
        String topic = normalized.isBlank() ? PROFILE_TOPIC : normalized;
        String planning = knowledgeService.recall(
                topic + " 搜索规划、领域术语和可信信息源规则", SKILL_NAME);
        String decision = knowledgeService.recall(
                topic + " 高价值信息判断、筛选和行动优先级标准", SKILL_NAME);
        return new ScoutExecutionContext(normalized, planning, decision);
    }

    public ScoutExecutionContext forScheduledRun() {
        return forExplicitQuery("");
    }
}
