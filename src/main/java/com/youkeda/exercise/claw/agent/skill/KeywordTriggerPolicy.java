package com.youkeda.exercise.claw.agent.skill;

import org.springframework.stereotype.Component;
import java.util.*;

@Component("keywordTriggerPolicy")
public class KeywordTriggerPolicy implements SkillTriggerPolicy {

    private final Map<String, List<String>> triggerKeywords;

    public KeywordTriggerPolicy(TriggerProperties properties) {
        this.triggerKeywords = properties.getTriggers();
    }

    @Override
    public SkillTriggerMatch match(String message, Optional<SkillSession> currentSession) {
        if (message == null || message.isEmpty()) return SkillTriggerMatch.noMatch();

        List<String> matchedSkills = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : triggerKeywords.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (message.contains(keyword)) {
                    matchedSkills.add(entry.getKey());
                    break;
                }
            }
        }

        if (matchedSkills.isEmpty()) return SkillTriggerMatch.noMatch();

        if (matchedSkills.size() == 1) {
            String skill = matchedSkills.get(0);
            return new SkillTriggerMatch(true, 0.85, "keyword match: " + skill, false);
        }

        // Multiple matches — let SkillRouter resolve by priority
        return new SkillTriggerMatch(true, 0.7, "multiple keyword matches: " + matchedSkills, false);
    }
}
