package com.youkeda.exercise.claw.agent.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@ConfigurationProperties(prefix = "claw.skill-routing")
public class TriggerProperties {
    private Map<String, List<String>> triggers = new LinkedHashMap<>();

    public Map<String, List<String>> getTriggers() { return triggers; }
    public void setTriggers(Map<String, List<String>> triggers) { this.triggers = triggers; }
}
