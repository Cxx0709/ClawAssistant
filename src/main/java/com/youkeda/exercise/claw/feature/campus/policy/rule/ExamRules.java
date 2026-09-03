package com.youkeda.exercise.claw.feature.campus.policy.rule;

import java.util.List;

public class ExamRules implements PolicyRule {

    private static final List<String> AUTO_PUSH = List.of("FINAL_EXAM", "CET");

    @Override
    public List<String> getAutoPushTypes() {
        return AUTO_PUSH;
    }
}
