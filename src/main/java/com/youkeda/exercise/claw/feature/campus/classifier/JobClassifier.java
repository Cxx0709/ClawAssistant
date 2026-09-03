package com.youkeda.exercise.claw.feature.campus.classifier;

import com.youkeda.exercise.claw.domain.campus.NotificationItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 就业分类器。
 * 规则优先（0 token），未命中返回 null 表示非就业通知。
 * Phase 2 仅规则分类，后续可扩展 LLM 分类。
 */
@Component
@ConditionalOnProperty(name = "campus.enabled", havingValue = "true")
public class JobClassifier {

    private static final Logger log = LoggerFactory.getLogger(JobClassifier.class);

    private static final List<Rule> RULES = List.of(
        // 招聘会/双选会 — 自动推送
        new Rule("招聘会|双选会",        "CAREER_FAIR"),
        new Rule("供需见面",             "CAREER_FAIR"),
        new Rule(".*招聘.*会",           "CAREER_FAIR"),

        // 名企宣讲会 — 自动推送
        new Rule("宣讲会",               "ELITE_TALK"),
        new Rule(".*宣讲.*",             "ELITE_TALK"),

        // 实习/校招 — 询问用户
        new Rule("实习|暑期.*实习",       "INTERN_RECRUIT"),
        new Rule("校招|校园.*招聘",       "INTERN_RECRUIT"),
        new Rule("应届.*招聘|毕业生.*招聘", "INTERN_RECRUIT"),

        // 就业指导 — 询问用户
        new Rule("就业指导",             "JOB_GUIDANCE"),
        new Rule("职业规划|生涯规划",      "JOB_GUIDANCE"),
        new Rule("简历.*指导|简历.*培训",  "JOB_GUIDANCE"),
        new Rule("面试.*技巧|面试.*培训",  "JOB_GUIDANCE"),

        // 其他就业 — 询问用户
        new Rule("招聘.*通知|招聘.*信息",  "OTHER_JOB"),
        new Rule("就业.*通知|就业.*信息",  "OTHER_JOB"),
        new Rule("求职|岗位|录用",        "OTHER_JOB"),
        new Rule(".*招聘",               "OTHER_JOB")  // 兜底：以"招聘"结尾
    );

    /**
     * 分类通知。返回匹配的就业类型，或 null 表示非就业通知。
     */
    public String classify(NotificationItem item) {
        String title = item.getTitle();
        if (title == null || title.isBlank()) return null;

        for (Rule rule : RULES) {
            if (rule.pattern().matcher(title).find()) {
                log.debug("就业分类命中 | title={} | type={}", title, rule.type());
                return rule.type();
            }
        }
        return null; // 非就业通知
    }

    private record Rule(Pattern pattern, String type) {
        Rule(String keyword, String type) {
            this(Pattern.compile(keyword, Pattern.CASE_INSENSITIVE), type);
        }
    }
}
