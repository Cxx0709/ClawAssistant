package com.youkeda.exercise.claw.agent.skill;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 旅游规划专用触发策略。
 *
 * <p>与 keywordTriggerPolicy 的区别：支持「非中文目的地 + 天数」组合（如「去 Bali 玩五天」），
 * 并区分「知识性问题」（旅游是什么意思）不触发。custom policy 通过现有
 * TriggerPolicyFactory 的 else 分支接入，不改 SkillRouter。
 */
@Component("travelTriggerPolicy")
public class TravelTriggerPolicy implements SkillTriggerPolicy {

    private static final String NUMBER = "[零一二三四五六七八九十百千万两\\d]+";

    /** 旅行规划请求：N天游/行程；或 去/到 + 目的地 + 玩/旅游 + N天；或 规划/安排...旅游/行程 */
    private static final Pattern TRAVEL_REQUEST = Pattern.compile(
            "(?:" + NUMBER + ")[日天](?:游|行程)"
                    + "|(?:去|到)[^，。！？]{1,16}(?:玩|逛|旅游|旅行)(?:" + NUMBER + ")[日天]"
                    + "|(?:规划|安排|制定|设计|生成|策划|准备|做)"
                    + ".{0,20}(?:旅游|旅行|出游|行程|攻略)");

    /** 已激活 travel 时的续接意图 */
    private static final Pattern TRAVEL_CONTINUATION = Pattern.compile(
            "重新规划|再做(?:一个|一份)?(?:行程|方案)?|换(?:个|一个)?(?:地方|目的地)"
                    + "|(?:修改|调整|改成|改为|优化|细化).{0,12}"
                    + "(?:行程|预算|人数|出发|目的地|日期|天数|住宿|酒店|交通|景点|" + NUMBER + "天)");

    /** 知识性问题，不触发 */
    private static final Pattern KNOWLEDGE_QUESTION = Pattern.compile(
            "是什么意思|什么含义|有哪些经典路线");

    @Override
    public SkillTriggerMatch match(String message, Optional<SkillSession> currentSession) {
        if (message == null || message.isBlank()) return SkillTriggerMatch.noMatch();

        boolean activeTravel = currentSession
                .map(s -> "travel".equals(s.activeSkill()))
                .orElse(false);
        if (activeTravel && TRAVEL_CONTINUATION.matcher(message).find()) {
            return new SkillTriggerMatch(true, 0.92, "active travel continuation", true);
        }
        if (KNOWLEDGE_QUESTION.matcher(message).find()) {
            return SkillTriggerMatch.noMatch();
        }
        if (!TRAVEL_REQUEST.matcher(message).find()) {
            return SkillTriggerMatch.noMatch();
        }
        return new SkillTriggerMatch(true, 0.9, "travel planning intent", false);
    }
}
