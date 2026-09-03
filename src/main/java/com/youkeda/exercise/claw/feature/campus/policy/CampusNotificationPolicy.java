package com.youkeda.exercise.claw.feature.campus.policy;

import com.youkeda.exercise.claw.domain.campus.CampusConfig;
import com.youkeda.exercise.claw.domain.campus.ExamClassification;
import com.youkeda.exercise.claw.domain.campus.NoticeType;
import com.youkeda.exercise.claw.feature.campus.store.PendingAskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class CampusNotificationPolicy {

    private static final Logger log = LoggerFactory.getLogger(CampusNotificationPolicy.class);

    private final PendingAskStore pendingAskStore;

    public CampusNotificationPolicy(PendingAskStore pendingAskStore) {
        this.pendingAskStore = pendingAskStore;
    }

    public Decision decide(ExamClassification classification, CampusConfig config) {
        NoticeType type = classification.type();

        if (type == NoticeType.NON_EXAM || type == NoticeType.UNKNOWN) {
            return Decision.IGNORE;
        }

        String typeName = type.name();

        // 1. 检查自动推送列表
        if (config.getPreferences().getAutoPushTypes().contains(typeName)) {
            return Decision.NOTIFY;
        }

        // 2. 查最新一次用户回答（按 asked_at DESC）
        Optional<String> answer = pendingAskStore.findLatestAnswer(typeName);
        if (answer.isPresent()) {
            return answer.get().equalsIgnoreCase("yes")
                ? Decision.NOTIFY : Decision.SKIP;
        }

        // 3. 从未问过
        return Decision.ASK;
    }

    public enum Decision {
        NOTIFY,  // 自动推送
        ASK,     // 询问用户是否要推
        SKIP,    // 用户之前说不需要
        IGNORE   // 非考试通知，直接忽略
    }
}
