package com.youkeda.exercise.claw.feature.campus.notification;
import com.youkeda.exercise.claw.notification.NotificationSource;

import com.youkeda.exercise.claw.feature.campus.classifier.ExamLLMClassifier;
import com.youkeda.exercise.claw.feature.campus.classifier.ExamRuleClassifier;
import com.youkeda.exercise.claw.feature.campus.collector.CampusNoticeCollector;
import com.youkeda.exercise.claw.domain.campus.CampusConfig;
import com.youkeda.exercise.claw.domain.campus.ExamClassification;
import com.youkeda.exercise.claw.domain.campus.NoticeType;
import com.youkeda.exercise.claw.domain.campus.NotificationItem;
import com.youkeda.exercise.claw.feature.campus.policy.DefaultPolicy;
import com.youkeda.exercise.claw.feature.campus.policy.NotificationPolicy;
import com.youkeda.exercise.claw.feature.campus.policy.rule.ExamRules;
import com.youkeda.exercise.claw.feature.campus.store.CampusConfigStore;
import com.youkeda.exercise.claw.feature.campus.store.CampusNotificationStore;
import com.youkeda.exercise.claw.feature.campus.store.PendingAskStore;
import com.youkeda.exercise.claw.notification.NotificationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "campus.enabled", havingValue = "true")
public class ExamSource implements NotificationSource {

    private static final Logger log = LoggerFactory.getLogger(ExamSource.class);

    private final CampusNoticeCollector collector;
    private final CampusNotificationStore noticeStore;
    private final DefaultPolicy policy;
    private final CampusConfigStore configStore;
    private final PendingAskStore pendingAskStore;
    private final NotificationEventPublisher publisher;
    private final ExamRuleClassifier ruleClassifier;
    private final ExamLLMClassifier llmClassifier;

    public ExamSource(CampusNoticeCollector collector,
                      CampusNotificationStore noticeStore,
                      DefaultPolicy policy,
                      CampusConfigStore configStore,
                      PendingAskStore pendingAskStore,
                      NotificationEventPublisher publisher,
                      ExamRuleClassifier ruleClassifier,
                      ExamLLMClassifier llmClassifier) {
        this.collector = collector;
        this.noticeStore = noticeStore;
        this.policy = policy;
        this.configStore = configStore;
        this.pendingAskStore = pendingAskStore;
        this.publisher = publisher;
        this.ruleClassifier = ruleClassifier;
        this.llmClassifier = llmClassifier;
    }

    @Override
    public String getName() { return "EXAM"; }

    @Override
    public void check() {
        try {
            CampusConfig config = configStore.get();
            if (config == null || config.getSchool() == null || config.getSchool().isBlank()) return;

            String schoolUrl = resolveSchoolUrl(config.getSchool());
            if (schoolUrl == null) {
                log.warn("未知学校，跳过考试通知检查 | school={}", config.getSchool());
                return;
            }

            log.info("===== ExamSource 检查 | school={} =====", config.getSchool());

            // 1. 采集
            List<NotificationItem> notices = collector.collect(schoolUrl);
            if (notices.isEmpty()) return;

            // 2. 去重
            List<NotificationItem> newNotices = noticeStore.deduplicate(notices);
            if (newNotices.isEmpty()) return;

            // 3. 逐条处理：分类 → 策略决策 → 推送
            for (NotificationItem notice : newNotices) {
                processNotice(notice, config);
            }

            log.info("===== ExamSource 完成 | school={} | new={} =====",
                    config.getSchool(), newNotices.size());
        } catch (Exception e) {
            log.error("ExamSource 检查异常", e);
        }
    }

    private void processNotice(NotificationItem notice, CampusConfig config) {
        // 1. 分类：规则优先，LLM 兜底
        ExamClassification result = ruleClassifier.classify(notice);
        if (result == null) {
            result = llmClassifier.classify(notice);
        }
        if (result == null || result.type() == NoticeType.UNKNOWN) {
            log.warn("通知分类失败，下次重试 | title={}", notice.getTitle());
            return;
        }

        // 更新通知的分类结果（NoticeType 枚举 → String）
        notice.setType(result.type().name());
        notice.setConfidence(result.confidence());
        notice.setScoreSource(result.scoreSource());
        notice.setClassifierReason(result.reason());
        notice.setProcessedAt(System.currentTimeMillis() / 1000);
        noticeStore.update(notice);

        // 2. 策略决策（批次 3：统一模型，直接 decision 用 NotificationItem）
        NotificationPolicy.Decision decision = policy.decide(
            notice, config, new ExamRules());

        // 3. 执行决策
        switch (decision) {
            case NOTIFY -> notifyUser(notice);
            case ASK -> askUser(notice);
            case SKIP -> log.debug("用户已忽略 {}，跳过", notice.getType());
            case IGNORE -> log.debug("非考试通知，忽略");
        }
    }

    private void notifyUser(NotificationItem notice) {
        String typeDisplayName = typeDisplayName(notice.getType());

        String message = "📌 考试提醒\n"
            + "「" + notice.getTitle() + "」\n"
            + "类型: " + typeDisplayName + "\n"
            + (notice.getPublishAt() != null && !notice.getPublishAt().isBlank()
                ? "发布日期: " + notice.getPublishAt() + "\n" : "")
            + "详情: " + notice.getUrl();

        publisher.publish("EXAM", "📌 考试提醒", message, 4);
        log.info("考试通知已推送 | title={}", notice.getTitle());
    }

    private void askUser(NotificationItem notice) {
        String typeDisplayName = typeDisplayName(notice.getType());

        String question = "检测到新的「" + typeDisplayName + "」通知："
            + notice.getTitle() + "，\n需要提醒你吗？（回复 需要/不需要）";

        publisher.publish("EXAM", "需要用户确认", question, 3);

        pendingAskStore.save("EXAM", notice.getType(), question, "PENDING");
        log.info("已询问用户是否推送 | type={} | title={}",
                notice.getType(), notice.getTitle());
    }

    private String typeDisplayName(String type) {
        if (type == null) return "未知";
        return switch (type) {
            case "FINAL_EXAM" -> "期末考试";
            case "CET" -> "大学英语四六级";
            case "RETAKE" -> "补考/重修";
            case "MIDTERM" -> "期中考试";
            case "COMPUTER_LEVEL" -> "计算机等级考试";
            case "PUTONGHUA" -> "普通话测试";
            case "OTHER_EXAM" -> "其他考试";
            default -> "未知类型";
        };
    }

    private String resolveSchoolUrl(String school) {
        if (school == null) return null;
        if (school.contains("南邮") || school.contains("南京邮电")) {
            return CampusNoticeCollector.NJUPT_NOTICE_URL;
        }
        log.warn("未配置的学校: {}", school);
        return null;
    }
}
