package com.youkeda.exercise.claw.agent.runtime;

import java.util.List;

/**
 * 用户消息 → 定时任务动作意图 解析器。
 *
 * <p>与旧实现 {@code isScheduleTaskRequest} 的本质区别：<b>用动作动词判断意图，
 * 不用「定时」这类任务名词</b>。「我有哪些定时提醒」里的「定时」只是对象，
 * 若因命中「定时」就判定为创建，会导致防幻觉 guard 误触发（详见 ExecutionLoop）。
 *
 * <p>匹配顺序：DELETE → UPDATE → QUERY → CREATE → NONE。动作动词（取消/修改/查看）
 * 优先于创建动词（设置/提醒我），保证「取消提醒我明天开会」这类复合句归类到更具体的动作。
 *
 * <p>创建意图判定用「创建动词 + 对象」且<b>动词在对象之前</b>的顺序正则：
 * 「设置定时提醒」命中创建；「定时提醒怎么设置」（设置在后，是疑问句）落 NONE，
 * 不会因误判为创建而触发 guard 重试死循环。
 */
public final class ScheduleIntentResolver {

    private ScheduleIntentResolver() {
    }

    // QUERY：查询提醒列表
    private static final List<String> QUERY_KEYWORDS = List.of(
            "有哪些", "什么提醒", "查看提醒", "查询提醒", "提醒列表",
            "我的提醒", "设置的提醒", "查提醒", "看看提醒", "看下提醒");

    // DELETE：取消/删除/移除
    private static final List<String> DELETE_KEYWORDS = List.of(
            "取消", "删除", "移除");

    // UPDATE：修改时间/内容
    private static final List<String> UPDATE_KEYWORDS = List.of(
            "改成", "改到", "修改", "调整", "提前", "推迟");

    // CREATE：直接创建动词（无需对象前置即可断定创建）
    private static final List<String> CREATE_VERBS = List.of(
            "提醒我", "帮我提醒", "提醒一下", "提醒下",
            "定闹钟", "定个", "分钟后", "小时提醒");

    /** CREATE：创建动词（设置/创建/添加）+ 对象（提醒/定时/闹钟），动词必须在对象之前 */
    private static final String CREATE_SETUP_PATTERN = ".*(设置|创建|添加).*(提醒|定时|闹钟).*";

    /** CREATE：周期模式，如「每天早上8点（喝水）」 */
    private static final String PERIODIC_PATTERN = ".*(每天|每周|每月|每隔).*[0-9时点分秒早中晚上午下午].*";

    /**
     * 解析用户消息的定时任务动作意图。空白/空消息返回 NONE。
     */
    public static ScheduleIntent resolve(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return ScheduleIntent.NONE;
        }
        String msg = userMessage.replaceAll("\\s+", "");
        if (containsAny(msg, DELETE_KEYWORDS)) return ScheduleIntent.DELETE;
        if (containsAny(msg, UPDATE_KEYWORDS)) return ScheduleIntent.UPDATE;
        if (containsAny(msg, QUERY_KEYWORDS)) return ScheduleIntent.QUERY;
        if (isCreateRequest(msg)) return ScheduleIntent.CREATE;
        return ScheduleIntent.NONE;
    }

    private static boolean isCreateRequest(String msg) {
        if (containsAny(msg, CREATE_VERBS)) return true;
        if (msg.matches(CREATE_SETUP_PATTERN)) return true;
        return msg.matches(PERIODIC_PATTERN);
    }

    private static boolean containsAny(String msg, List<String> keywords) {
        for (String kw : keywords) {
            if (msg.contains(kw)) return true;
        }
        return false;
    }
}
