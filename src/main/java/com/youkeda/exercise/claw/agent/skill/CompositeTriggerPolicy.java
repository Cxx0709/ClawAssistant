package com.youkeda.exercise.claw.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 组合触发策略：将多个策略按优先级链式执行，任一匹配即返回。
 *
 * <p>支持策略链配置，允许不同策略互补（如关键词 + 语义 + 自定义规则）。
 * 优先级由 order 属性决定，数值越小优先级越高。
 */
@Component("compositeTriggerPolicy")
public class CompositeTriggerPolicy implements SkillTriggerPolicy {

    private static final Logger log = LoggerFactory.getLogger(CompositeTriggerPolicy.class);

    private final List<TriggerPolicyEntry> policyChain;

    public CompositeTriggerPolicy(List<TriggerPolicyEntry> policyChain) {
        this.policyChain = policyChain != null ? policyChain : new ArrayList<>();
        this.policyChain.sort(Comparator.comparingInt(TriggerPolicyEntry::order));
        log.info("CompositeTriggerPolicy initialized with {} policies", this.policyChain.size());
    }

    @Override
    public SkillTriggerMatch match(String message, Optional<SkillSession> currentSession) {
        if (message == null || message.isBlank()) return SkillTriggerMatch.noMatch();

        for (TriggerPolicyEntry entry : policyChain) {
            try {
                SkillTriggerMatch match = entry.policy().match(message, currentSession);
                if (match.matched()) {
                    log.debug("Policy [{}] matched with confidence {}: {}",
                            entry.name(), match.confidence(), match.reason());
                    return match;
                }
            } catch (Exception e) {
                log.warn("Policy [{}] failed: {}", entry.name(), e.getMessage());
            }
        }

        return SkillTriggerMatch.noMatch();
    }

    /**
     * 策略条目：包含策略实例、名称和优先级。
     */
    public record TriggerPolicyEntry(
            SkillTriggerPolicy policy,
            String name,
            int order
    ) {}
}
