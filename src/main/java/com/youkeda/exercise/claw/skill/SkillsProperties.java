package com.youkeda.exercise.claw.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@ConfigurationProperties(prefix = "claw")
public class SkillsProperties {

    private Set<String> globalTools = new LinkedHashSet<>();
    private Set<String> commonCapabilityTools = new LinkedHashSet<>();
    private KnowledgeProperties knowledge = new KnowledgeProperties();
    private Map<String, SkillDefinition> skills = new LinkedHashMap<>();
    private Map<String, WorkflowDefinition> workflows = new LinkedHashMap<>();
    private Map<String, String> skillWorkflowBindings = new LinkedHashMap<>();

    public Set<String> getGlobalTools() { return globalTools; }
    public void setGlobalTools(Set<String> globalTools) { this.globalTools = globalTools; }

    public Set<String> getCommonCapabilityTools() { return commonCapabilityTools; }
    public void setCommonCapabilityTools(Set<String> commonCapabilityTools) { this.commonCapabilityTools = commonCapabilityTools; }

    public KnowledgeProperties getKnowledge() { return knowledge; }
    public void setKnowledge(KnowledgeProperties knowledge) {
        this.knowledge = knowledge == null ? new KnowledgeProperties() : knowledge;
    }

    public Map<String, SkillDefinition> getSkills() { return skills; }
    public void setSkills(Map<String, SkillDefinition> skills) { this.skills = skills; }

    public Map<String, WorkflowDefinition> getWorkflows() { return workflows; }
    public void setWorkflows(Map<String, WorkflowDefinition> workflows) { this.workflows = workflows; }

    public Map<String, String> getSkillWorkflowBindings() { return skillWorkflowBindings; }
    public void setSkillWorkflowBindings(Map<String, String> skillWorkflowBindings) { this.skillWorkflowBindings = skillWorkflowBindings; }

    public static class KnowledgeProperties {
        private boolean globalEnabled;

        public boolean isGlobalEnabled() { return globalEnabled; }
        public void setGlobalEnabled(boolean globalEnabled) { this.globalEnabled = globalEnabled; }
    }
}
