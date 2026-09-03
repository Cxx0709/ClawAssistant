package com.youkeda.exercise.claw.feature.campus.notification;
import com.youkeda.exercise.claw.notification.NotificationSource;

import com.youkeda.exercise.claw.feature.campus.classifier.ActivityClassifier;
import com.youkeda.exercise.claw.feature.campus.collector.CompetitionCollector;
import com.youkeda.exercise.claw.domain.campus.NotificationItem;
import com.youkeda.exercise.claw.feature.campus.policy.DefaultPolicy;
import com.youkeda.exercise.claw.feature.campus.policy.NotificationPolicy;
import com.youkeda.exercise.claw.feature.campus.policy.rule.ActivityRules;
import com.youkeda.exercise.claw.feature.campus.store.CampusNotificationStore;
import com.youkeda.exercise.claw.feature.campus.store.PendingAskStore;
import com.youkeda.exercise.claw.notification.NotificationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 活动通知 Source。
 * 复用教务处通知列表，通过 ActivityClassifier 筛选活动相关通知。
 */
@Component
@ConditionalOnProperty(name = "campus.enabled", havingValue = "true")
public class ActivitySource implements NotificationSource {

    private static final Logger log = LoggerFactory.getLogger(ActivitySource.class);
    private static final ActivityRules RULES = new ActivityRules();

    private final CompetitionCollector collector;
    private final CampusNotificationStore store;
    private final ActivityClassifier classifier;
    private final DefaultPolicy policy;
    private final PendingAskStore pendingAskStore;
    private final NotificationEventPublisher publisher;

    public ActivitySource(CompetitionCollector collector,
                          CampusNotificationStore store,
                          ActivityClassifier classifier,
                          DefaultPolicy policy,
                          PendingAskStore pendingAskStore,
                          NotificationEventPublisher publisher) {
        this.collector = collector;
        this.store = store;
        this.classifier = classifier;
        this.policy = policy;
        this.pendingAskStore = pendingAskStore;
        this.publisher = publisher;
    }

    @Override
    public String getName() { return "ACTIVITY"; }

    @Override
    public void check() {
        log.info("===== ActivitySource 检查 =====");

        // 1. 采集（复用教务处通知列表）
        List<NotificationItem> items = collector.collect();
        if (items.isEmpty()) return;

        // 2. 去重（按 url + source 联合键）
        List<NotificationItem> newItems = store.deduplicate(items);
        if (newItems.isEmpty()) return;

        // 3. 逐条分类→决策→推送
        for (NotificationItem item : newItems) {
            processItem(item);
        }

        log.info("===== ActivitySource 完成 | new={} =====", newItems.size());
    }

    private void processItem(NotificationItem item) {
        // 分类
        String type = classifier.classify(item);
        if (type == null) {
            log.debug("非活动通知，跳过 | title={}", item.getTitle());
            return;
        }
        item.setType(type);

        // 更新存储
        store.update(item);

        // 决策
        NotificationPolicy.Decision decision = policy.decide(item, null, RULES);

        switch (decision) {
            case NOTIFY -> notifyUser(item);
            case ASK -> askUser(item);
            case SKIP -> log.debug("用户已忽略活动 {}，跳过", type);
            case IGNORE -> log.debug("忽略活动通知");
        }
    }

    private void notifyUser(NotificationItem item) {
        String typeDisplayName = typeDisplayName(item.getType());

        String message = "🎉 活动提醒\n"
            + "「" + item.getTitle() + "」\n"
            + "类型: " + typeDisplayName + "\n"
            + (item.getPublishAt() != null && !item.getPublishAt().isBlank()
                ? "发布日期: " + item.getPublishAt() + "\n" : "")
            + "详情: " + item.getUrl();

        publisher.publish("ACTIVITY", "🎉 活动提醒", message, 4);
        log.info("活动通知已推送 | title={}", item.getTitle());
    }

    private void askUser(NotificationItem item) {
        String typeDisplayName = typeDisplayName(item.getType());

        String question = "检测到新的「" + typeDisplayName + "」活动："
            + item.getTitle() + "，\n需要关注这类活动吗？（回复 需要/不需要）";

        publisher.publish("ACTIVITY", "需要用户确认", question, 3);

        pendingAskStore.save("ACTIVITY", item.getType(), question, "PENDING");
        log.info("已询问用户是否关注活动 | type={} | title={}", item.getType(), item.getTitle());
    }

    private String typeDisplayName(String type) {
        if (type == null) return "未知";
        return switch (type) {
            case "CAMPUS_EVENT" -> "全校活动";
            case "LECTURE" -> "讲座/报告";
            case "CLUB_ACTIVITY" -> "社团活动";
            case "OTHER_ACTIVITY" -> "其他活动";
            default -> type;
        };
    }
}
