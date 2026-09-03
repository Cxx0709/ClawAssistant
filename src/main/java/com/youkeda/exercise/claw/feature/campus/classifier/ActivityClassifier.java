package com.youkeda.exercise.claw.feature.campus.classifier;

import com.youkeda.exercise.claw.domain.campus.NotificationItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 活动分类器。
 * 规则优先（0 token），未命中返回 null 表示非活动通知。
 * Phase 2 仅规则分类，后续可扩展 LLM 分类。
 */
@Component
@ConditionalOnProperty(name = "campus.enabled", havingValue = "true")
public class ActivityClassifier {

    private static final Logger log = LoggerFactory.getLogger(ActivityClassifier.class);

    private static final List<Rule> RULES = List.of(
        // 大型全校活动 — 自动推送
        new Rule("校庆",              "CAMPUS_EVENT"),
        new Rule("晚会|文艺汇演",      "CAMPUS_EVENT"),
        new Rule("艺术节|文化节",      "CAMPUS_EVENT"),
        new Rule("运动会|体育节",      "CAMPUS_EVENT"),
        new Rule("全校|全体.*生.*活动", "CAMPUS_EVENT"),

        // 讲座/报告 — 询问用户
        new Rule("讲座|讲坛",          "LECTURE"),
        new Rule("报告会|学术报告",     "LECTURE"),
        new Rule("论坛|研讨会",        "LECTURE"),
        new Rule("公开课|名师",        "LECTURE"),

        // 社团活动 — 询问用户
        new Rule("社团|招新",          "CLUB_ACTIVITY"),
        new Rule("俱乐部|协会",        "CLUB_ACTIVITY"),
        new Rule("兴趣小组",           "CLUB_ACTIVITY"),

        // 其他活动 — 询问用户
        new Rule("活动.*报名|报名.*活动", "OTHER_ACTIVITY"),
        new Rule("征集|征稿",           "OTHER_ACTIVITY"),
        new Rule("选拔|招募",           "OTHER_ACTIVITY"),
        new Rule(".*活动",             "OTHER_ACTIVITY")  // 兜底：包含"活动"二字
    );

    /**
     * 分类通知。返回匹配的活动类型，或 null 表示非活动通知。
     */
    public String classify(NotificationItem item) {
        String title = item.getTitle();
        if (title == null || title.isBlank()) return null;

        for (Rule rule : RULES) {
            if (rule.pattern().matcher(title).find()) {
                log.debug("活动分类命中 | title={} | type={}", title, rule.type());
                return rule.type();
            }
        }
        return null; // 非活动通知
    }

    private record Rule(Pattern pattern, String type) {
        Rule(String keyword, String type) {
            this(Pattern.compile(keyword, Pattern.CASE_INSENSITIVE), type);
        }
    }
}
