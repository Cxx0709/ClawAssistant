package com.youkeda.exercise.claw.feature.campus.classifier;

import com.youkeda.exercise.claw.domain.campus.ExamClassification;
import com.youkeda.exercise.claw.domain.campus.NotificationItem;
import com.youkeda.exercise.claw.domain.campus.NoticeType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class ExamRuleClassifier {

    /**
     * 规则列表，按优先级降序排列。
     * priority=100 的具体考试优先于 priority=10 的泛化规则。
     */
    private static final List<Rule> RULES = List.of(
        new Rule("期末考试|期末安排|课程结束考试", NoticeType.FINAL_EXAM,      100, 0.95),
        new Rule("四六级|大学英语四六级|CET",     NoticeType.CET,              100, 0.95),
        new Rule("补考|重修",                      NoticeType.RETAKE,           100, 0.90),
        new Rule("期中考试",                      NoticeType.MIDTERM,          100, 0.90),
        new Rule("计算机.*等级考试|计算机等级",     NoticeType.COMPUTER_LEVEL,   90, 0.90),
        new Rule("普通话",                         NoticeType.PUTONGHUA,         90, 0.90),
        new Rule("考试.*安排|考试.*通知|考务",      NoticeType.OTHER_EXAM,        10, 0.85)
    );

    public ExamClassification classify(NotificationItem notice) {
        String title = notice.getTitle();
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(title).matches()) {
                return new ExamClassification(
                    rule.type(), rule.confidence(),
                    "标题命中规则: " + rule.keyword(),
                    "RULE"
                );
            }
        }
        return null; // 无法确定，交给 LLM
    }

    private record Rule(String keyword, Pattern pattern,
                        int priority, NoticeType type, double confidence) {
        Rule(String keyword, NoticeType type, int priority, double confidence) {
            this(keyword, Pattern.compile(".*(?:" + keyword + ").*",
                    Pattern.CASE_INSENSITIVE),
                 priority, type, confidence);
        }
    }
}
