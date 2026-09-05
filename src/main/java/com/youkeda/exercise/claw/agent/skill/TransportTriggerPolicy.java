package com.youkeda.exercise.claw.agent.skill;

import org.springframework.stereotype.Component;
import java.util.Optional;

@Component("transportTriggerPolicy")
public class TransportTriggerPolicy implements SkillTriggerPolicy {

    private static final java.util.Set<String> TRANSPORT_VERBS = java.util.Set.of(
            "坐车", "怎么去", "怎么走", "坐地铁", "坐公交");

    private static final java.util.Set<String> SEARCH_VERBS = java.util.Set.of(
            "搜索", "查一下", "有什么", "推荐");

    @Override
    public SkillTriggerMatch match(String message, Optional<SkillSession> session) {
        if (message == null || message.isBlank()) return SkillTriggerMatch.noMatch();

        boolean hasTransportVerb = TRANSPORT_VERBS.stream().anyMatch(message::contains);
        if (!hasTransportVerb) return SkillTriggerMatch.noMatch();

        // Has location word + transport verb -> high confidence transport
        boolean hasPlace = message.contains("西湖") || message.contains("上海")
                || message.contains("北京") || message.contains("杭州")
                || message.contains("广州") || message.contains("深圳")
                || message.contains("地铁") || message.contains("公交");

        // Has search verb -> lower confidence (general search intent, not transport-specific)
        boolean hasSearchVerb = SEARCH_VERBS.stream().anyMatch(message::contains);

        if (hasTransportVerb && hasPlace && !hasSearchVerb) {
            return new SkillTriggerMatch(true, 0.9, "transport: place + verb", false);
        } else if (hasTransportVerb) {
            // 坐车/怎么去 等本身已是交通出行意图信号。
            // 置信度必须 >= SkillRouter.route() 的 0.8 门槛，否则该匹配会被丢弃，
            // 导致「怎么去X」永远路由不到 transport 技能。
            return new SkillTriggerMatch(true, 0.85, "transport: verb only", false);
        }

        return SkillTriggerMatch.noMatch();
    }
}
