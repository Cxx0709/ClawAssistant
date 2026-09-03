package com.youkeda.exercise.claw.feature.campus.policy;

import com.youkeda.exercise.claw.domain.campus.CampusConfig;
import com.youkeda.exercise.claw.domain.campus.NotificationItem;
import com.youkeda.exercise.claw.feature.campus.policy.rule.PolicyRule;
import com.youkeda.exercise.claw.feature.campus.store.PendingAskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnProperty(name = "campus.enabled", havingValue = "true")
public class DefaultPolicy implements NotificationPolicy {

    private static final Logger log = LoggerFactory.getLogger(DefaultPolicy.class);

    private final PendingAskStore pendingAskStore;

    public DefaultPolicy(PendingAskStore pendingAskStore) {
        this.pendingAskStore = pendingAskStore;
    }

    @Override
    public Decision decide(NotificationItem item, CampusConfig config, PolicyRule rule) {
        String type = item.getType();
        if (type == null || "IGNORE".equals(type) || "UNKNOWN".equals(type)) {
            return Decision.IGNORE;
        }

        // 1. 自动推送（白名单匹配）
        if (rule.shouldAutoNotify(item)) {
            return Decision.NOTIFY;
        }

        // 2. 查历史回答
        String source = item.getSource();
        Optional<String> answer = pendingAskStore.findLatestAnswer(source, type);
        if (answer.isPresent()) {
            return answer.get().equalsIgnoreCase("yes") ? Decision.NOTIFY : Decision.SKIP;
        }

        // 3. 首次见，问用户
        return Decision.ASK;
    }
}
