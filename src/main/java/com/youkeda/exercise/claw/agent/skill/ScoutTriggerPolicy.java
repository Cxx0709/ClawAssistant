package com.youkeda.exercise.claw.agent.skill;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 信息猎手确定性触发策略。
 *
 * <p>批次 2（ScoutTriggerPolicy 同名双包合并提前）：原 feature/scout/ScoutTriggerPolicy
 * 的静态判定逻辑迁入本类，消除 agent → feature 依赖（ArchitectureTest 例外随之移除）。
 * 本类既是 {@link SkillTriggerPolicy} 接口实现（bean 名 {@code scoutTriggerPolicy}，
 * skills.yml 依赖），又是静态工具提供者（{@link #hasExplicitRequest}/{@link #isCancellation}，
 * 供 {@code ScoutTool}、{@code InformationScoutIntentResolver} 使用）。
 *
 * <p>信息猎手开销较大且会主动联网采集，因此不能仅凭用户兴趣、话题相关性、
 * 历史对话或模型推断触发。当前消息必须包含明确的查找信息意图。
 */
@Component("scoutTriggerPolicy")
public class ScoutTriggerPolicy implements SkillTriggerPolicy {

    private static final Pattern NEGATED_REQUEST = Pattern.compile(
            "(?:不用|不要|别|无需|不需要|不想|不希望|不愿意|拒绝|禁止|不许)"
                    + ".{0,10}(?:查|搜|找|检索|调研|启动|运行|调用|开启|使用|信息猎手)");

    private static final Pattern NON_REQUEST_MENTION = Pattern.compile(
            "(?:(?:为什么|为何|怎么|如何|是否|会不会|能不能|能否|可不可以).{0,12}"
                    + "(?:调用|使用|启动|运行).{0,6}信息猎手)"
                    + "|(?:信息猎手.{0,8}(?:是什么|怎么用|如何用))");

    private static final Pattern DIRECT_SCOUT_REQUEST = Pattern.compile(
            "(?:启动|运行|调用|开启|使用|用一下|让).{0,6}信息猎手"
                    + "|信息猎手.{0,6}(?:启动|运行|调用|开启|查|找|搜)");

    /**
     * 持续监控动作：用户要建立"未来持续关注"关系。
     * 判别核心是动词，不是时间/主题词。频率词（每天/每周/定期）刻意不包含，
     * 避免与 create_schedule_task（定时提醒）抢语义。
     */
    private static final Pattern MONITORING_ACTION = Pattern.compile(
            "(?:关注|订阅|跟踪|追踪|监控|持续|搜集|收集|汇总|帮我留意|帮我盯着|有(?:什么)?消息(?:就|再)?通知我)");

    /**
     * 纯信息流主题词。
     * 实体词（比赛/竞赛/岗位/版本/活动/番剧/课程/考试/游戏/项目）已移除，
     * 否则"关注这场比赛""订阅这个活动"会被信息猎手抢走。
     */
    private static final Pattern INFO_STREAM_TOPIC = Pattern.compile(
            "(?:新闻|资讯|动态|消息|趋势|机会|政策|变化|情报|资料|值得关注|更新|前沿|业界|行业)");

    /** 即时查询信号：用户想知道"现在"的答案 → 不得进入后台任务 */
    private static final Pattern REALTIME_QUERY = Pattern.compile(
            "(?:查|搜(?!集)|看|找|问|推荐|告诉|介绍|发生了什么|出了什么|有什么大事)");

    @Override
    public SkillTriggerMatch match(String message, Optional<SkillSession> session) {
        if (message == null || message.isBlank()) return SkillTriggerMatch.noMatch();
        if (hasExplicitRequest(message)) {
            return new SkillTriggerMatch(true, 0.9, "scout explicit request", false);
        }
        return SkillTriggerMatch.noMatch();
    }

    /**
     * 只依据当前用户原话判断，不从历史或用户画像推断。
     */
    public static boolean hasExplicitRequest(String currentMessage) {
        if (currentMessage == null || currentMessage.isBlank()) {
            return false;
        }

        String normalized = currentMessage.replaceAll("\\s+", "");
        if (NEGATED_REQUEST.matcher(normalized).find()
                || NON_REQUEST_MENTION.matcher(normalized).find()) {
            return false;
        }
        if (DIRECT_SCOUT_REQUEST.matcher(normalized).find()) {
            return true;
        }

        // 持续监控语义：监控动词 ∧ 纯信息流主题 ∧ 非即时查询
        boolean monitoring = MONITORING_ACTION.matcher(normalized).find();
        boolean topic = INFO_STREAM_TOPIC.matcher(normalized).find();
        boolean realtime = REALTIME_QUERY.matcher(normalized).find();
        return monitoring && topic && !realtime;
    }

    public static boolean isCancellation(String currentMessage) {
        if (currentMessage == null || currentMessage.isBlank()) return false;
        String normalized = currentMessage.replaceAll("\\s+", "");
        return NEGATED_REQUEST.matcher(normalized).find()
                || normalized.matches(".*(?:算了|取消|不用了|不查了|别查了).*");
    }
}
