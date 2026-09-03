package com.youkeda.exercise.claw.feature.campus.policy.rule;

import java.util.ArrayList;
import java.util.List;

/**
 * 比赛通知推送规则。
 * 白名单中的比赛自动推送，其他比赛询问用户。
 */
public class CompetitionRules implements PolicyRule {

    /** 内置大赛白名单（自动推送） */
    private static final List<String> DEFAULT_WHITELIST = List.of(
        "CHALLENGE_CUP",
        "INTERNET_PLUS",
        "INNOVATION_EXPO"
    );

    private final List<String> whitelist;

    public CompetitionRules() {
        this(new ArrayList<>(DEFAULT_WHITELIST));
    }

    public CompetitionRules(List<String> whitelist) {
        this.whitelist = whitelist != null ? whitelist : new ArrayList<>(DEFAULT_WHITELIST);
    }

    @Override
    public List<String> getAutoPushTypes() {
        return whitelist;
    }
}
