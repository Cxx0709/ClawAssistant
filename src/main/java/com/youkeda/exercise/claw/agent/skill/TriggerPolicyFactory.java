package com.youkeda.exercise.claw.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class TriggerPolicyFactory {

    private static final Logger log = LoggerFactory.getLogger(TriggerPolicyFactory.class);

    private final ApplicationContext applicationContext;
    private static final String DEFAULT_POLICY_NAME = "keywordTriggerPolicy";

    /** 已知的策略 bean 名称（按优先级排序） */
    private static final List<String> POLICY_CHAIN = List.of(
            "semanticTriggerPolicy",  // 语义匹配优先
            "keywordTriggerPolicy",   // 关键词次之
            "compositeTriggerPolicy"  // 组合策略兜底
    );

    public TriggerPolicyFactory(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 获取指定名称的触发策略。
     * 如果指定了特定策略（如 travelTriggerPolicy），直接返回；
     * 否则返回组合策略链（语义 → 关键词 → 组合）。
     */
    public SkillTriggerPolicy getPolicy(String policyName) {
        // 明确指定了特定策略，直接返回
        if (policyName != null && !policyName.isBlank()
                && !DEFAULT_POLICY_NAME.equals(policyName)
                && !"compositeTriggerPolicy".equals(policyName)) {
            try {
                return applicationContext.getBean(policyName, SkillTriggerPolicy.class);
            } catch (Exception e) {
                log.warn("Trigger policy [{}] not found, falling back to composite chain", policyName);
            }
        }

        // 返回组合策略链
        return getCompositePolicy();
    }

    /**
     * 获取组合策略链（内部按优先级执行：语义 → 关键词 → 组合）。
     */
    public SkillTriggerPolicy getCompositePolicy() {
        List<CompositeTriggerPolicy.TriggerPolicyEntry> chain = new ArrayList<>();

        for (String policyName : POLICY_CHAIN) {
            try {
                SkillTriggerPolicy policy = applicationContext.getBean(policyName, SkillTriggerPolicy.class);
                int order = POLICY_CHAIN.indexOf(policyName);
                chain.add(new CompositeTriggerPolicy.TriggerPolicyEntry(policy, policyName, order));
            } catch (Exception e) {
                log.debug("Policy [{}] not available, skipping", policyName);
            }
        }

        return new CompositeTriggerPolicy(chain);
    }

    /**
     * 获取所有可用的触发策略。
     */
    public Map<String, SkillTriggerPolicy> getAllPolicies() {
        Map<String, SkillTriggerPolicy> policies = new HashMap<>();
        try {
            Map<String, SkillTriggerPolicy> beans = applicationContext.getBeansOfType(SkillTriggerPolicy.class);
            policies.putAll(beans);
        } catch (Exception e) {
            log.warn("Failed to retrieve all trigger policies", e);
        }
        return policies;
    }
}
