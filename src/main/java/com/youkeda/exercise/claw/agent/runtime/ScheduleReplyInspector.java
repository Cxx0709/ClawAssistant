package com.youkeda.exercise.claw.agent.runtime;

import java.util.List;

/**
 * 检查模型对定时提醒创建请求的回复内容，供防幻觉 guard 判定。
 *
 * <p>解决的问题：guard 若只看「用户是否要求创建」就拦截，会在 LLM 因信息不足
 * 反问「几点提醒你呢？」时同样注入「请先调用 create_schedule_task」重试，
 * 造成新一轮死循环。正确的拦截依据不是用户消息，而是<b>模型是否声称完成了
 * 一个并未执行的副作用</b>。
 *
 * <p>两个判定：
 * <ul>
 *   <li>{@link #claimsCreation}：回复是否声称「已创建/已设置」完成态（幻觉信号）</li>
 *   <li>{@link #asksForClarification}：回复是否在向用户澄清必要信息（正常，须放行）</li>
 * </ul>
 *
 * <p>两个判定都在 {@link ExecutionLoop} 中以「用户确为创建意图」为前提调用，
 * 避免非创建场景（如「已帮你找到」的搜索回复）误触发。
 */
public final class ScheduleReplyInspector {

    private ScheduleReplyInspector() {
    }

    /** 声称「已创建/已设置」完成态的词根（仅表示动作已完成，不含将来时） */
    private static final List<String> COMPLETION_MARKERS = List.of(
            "已创建", "创建成功", "创建完成", "创建好了", "创建好",
            "已设置", "设置成功", "设置完成", "设置好了", "设置好",
            "已添加", "添加成功", "添加好了",
            "已安排", "安排好了",
            "已定好", "定好了", "定好",
            "已经创建", "已经设置", "已经添加",
            "帮你创建好了", "帮你设置好了", "为你创建好了", "为你设置好了",
            "已帮你创建", "已帮你设置", "已为你创建", "已为你设置",
            "搞定");

    /** 澄清性回复信号：LLM 在索取必要信息，而非声称已执行 */
    private static final List<String> CLARIFYING_MARKERS = List.of(
            "?", "？", "什么", "几点", "多久", "多少", "何时", "哪个", "哪",
            "吗", "请", "确认", "告诉", "想要", "希望", "麻烦", "可以吗", "行吗");

    /**
     * 回复是否声称已经完成了定时提醒的创建/设置。
     *
     * <p>否定/失败声明（还没有、无法、失败）不构成幻觉。
     */
    public static boolean claimsCreation(String reply) {
        if (reply == null || reply.isBlank()) return false;
        String text = reply.replaceAll("\\s+", "");
        if (containsAny(text, List.of("没有", "还没", "还没有", "未能", "未成功", "失败", "无法"))) {
            return false;
        }
        return containsAny(text, COMPLETION_MARKERS);
    }

    /**
     * 回复是否在向用户澄清必要信息（提问/索取输入）。此时不应强制其调用创建工具。
     */
    public static boolean asksForClarification(String reply) {
        if (reply == null || reply.isBlank()) return false;
        return containsAny(reply, CLARIFYING_MARKERS);
    }

    private static boolean containsAny(String text, List<String> markers) {
        for (String marker : markers) {
            if (text.contains(marker)) return true;
        }
        return false;
    }
}
