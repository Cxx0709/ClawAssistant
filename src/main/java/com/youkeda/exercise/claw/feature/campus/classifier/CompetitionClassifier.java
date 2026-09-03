package com.youkeda.exercise.claw.feature.campus.classifier;

import com.youkeda.exercise.claw.domain.campus.NotificationItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 比赛分类器。
 * 规则优先（0 token），未命中返回 null 表示非比赛。
 * Phase 1 仅规则分类，Phase 2 可扩展 LLM 分类。
 */
@Component
@ConditionalOnProperty(name = "campus.enabled", havingValue = "true")
public class CompetitionClassifier {

    private static final Logger log = LoggerFactory.getLogger(CompetitionClassifier.class);

    private static final List<Rule> RULES = List.of(
        new Rule("挑战杯",          "CHALLENGE_CUP"),
        new Rule("互联网\\+",       "INTERNET_PLUS"),
        new Rule("大学生创新创业训练|大创", "INNOVATION_EXPO"),
        new Rule("数学建模|数模",    "ACADEMIC_COMPETITION"),
        new Rule("ACM|程序设计竞赛",  "ACADEMIC_COMPETITION"),
        new Rule("蓝桥杯",          "ACADEMIC_COMPETITION"),
        new Rule("计算机设计大赛",    "SKILL_COMPETITION"),
        new Rule("竞赛|比赛|选拔",    "OTHER_COMPETITION"),
        new Rule("报名.*大赛|大赛.*报名", "OTHER_COMPETITION")
    );

    /**
     * 分类通知。返回匹配的比赛类型，或 null 表示非比赛通知。
     */
    public String classify(NotificationItem item) {
        String title = item.getTitle();
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(title).find()) {
                log.debug("比赛分类命中 | title={} | type={}", title, rule.type());
                return rule.type();
            }
        }
        return null; // 非比赛通知
    }

    private record Rule(Pattern pattern, String type) {
        Rule(String keyword, String type) {
            this(Pattern.compile(keyword, Pattern.CASE_INSENSITIVE), type);
        }
    }
}
