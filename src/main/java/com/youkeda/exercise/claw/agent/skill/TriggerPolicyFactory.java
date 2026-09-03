package com.youkeda.exercise.claw.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class TriggerPolicyFactory {

    private static final Logger log = LoggerFactory.getLogger(TriggerPolicyFactory.class);

    private final ApplicationContext applicationContext;
    private static final String DEFAULT_POLICY_NAME = "keywordTriggerPolicy";

    public TriggerPolicyFactory(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public SkillTriggerPolicy getPolicy(String policyName) {
        String resolvedName = (policyName == null || policyName.isBlank()) ? DEFAULT_POLICY_NAME : policyName;
        try {
            return applicationContext.getBean(resolvedName, SkillTriggerPolicy.class);
        } catch (Exception e) {
            log.warn("Trigger policy [{}] not found, falling back to default [{}]", resolvedName, DEFAULT_POLICY_NAME, e);
            return applicationContext.getBean(DEFAULT_POLICY_NAME, SkillTriggerPolicy.class);
        }
    }
}
