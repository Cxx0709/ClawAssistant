package com.youkeda.exercise.claw.skill;
import com.youkeda.exercise.claw.agent.skill.SkillSession;
import com.youkeda.exercise.claw.agent.skill.SkillPendingCoordinator;

import com.youkeda.exercise.claw.agent.skill.ScoutTriggerPolicy;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class InformationScoutIntentResolver {

    private static final Pattern PROFILE_DISCOVERY = Pattern.compile(
            ".*(?:有什么值得关注|有什么新动态|有什么新消息|有什么新资讯).*"
                    + "|(?:启动|运行|开启|调用|使用|用一下)信息猎手");
    private static final Pattern TOPIC_MISSING = Pattern.compile(
            "(?:帮我|请|麻烦|给我|替我|为我|能不能|可以帮我)?"
                    + "(?:找找|找一下|找一找|搜搜看|搜一下|搜一搜|搜索一下|"
                    + "搜集|收集|汇总|"
                    + "查查|查一下|查一查|检索一下|调研一下)[吗呢吧？?]*");
    private static final Pattern TOPIC_REQUEST_PREFIX = Pattern.compile(
            "^(?:请|麻烦|能不能|可以)?\\s*"
                    + "(?:帮我|替我|给我|为我|我想|我需要)?\\s*"
                    + "(?:查一下|查一查|查查|找一下|找一找|找找|"
                    + "搜一下|搜一搜|搜搜看|搜索一下|搜集|收集|汇总|"
                    + "检索一下|调研一下|"
                    + "看看|看一下|关注(?:一下)?|跟踪(?:一下)?|追踪(?:一下)?|监控(?:一下)?|订阅(?:一下)?)"
                    + "\\s*[：:，,]?\\s*");
    private static final Pattern PENDING_NON_TOPIC = Pattern.compile(
            "(?:好的?|知道了?|明白了?|嗯+|哦+|行|可以|随便|都可以|不知道|"
                    + "没想好|暂时没有|以后再说)[。！!？?]*");
    private static final Pattern GENERIC_TOPIC_TERMS = Pattern.compile(
            "(?i)(?:最近|最新|近期|本周|今天|动态|更新|新闻|资讯|消息|趋势|机会|"
                    + "值得关注|相关|方面|内容|信息|资料|信息猎手|谢谢|感谢|然后呢?|"
                    + "好的?|知道了?|明白了?|不知道|随便|都可以|没想好|暂时没有|"
                    + "以后再说|使用|启动|运行|调用|开启|请|帮我|麻烦|给我|替我|"
                    + "为我|看看|看一下|关注|跟踪|追踪|监控|订阅)");

    public InformationScoutIntent resolve(String currentMessage, SkillSession session) {
        String message = currentMessage == null ? "" : currentMessage.trim();
        String normalized = message.replaceAll("\\s+", "");
        if (ScoutTriggerPolicy.isCancellation(message)) {
            return InformationScoutIntent.cancel();
        }
        if (session != null
                && session.hasPendingAction(SkillPendingCoordinator.START_INFORMATION_SCOUT)) {
            if (PENDING_NON_TOPIC.matcher(normalized).matches()
                    || !hasSubstantiveTopic(message)) {
                return InformationScoutIntent.cancel();
            }
            return InformationScoutIntent.topicSearch(message);
        }
        if (PROFILE_DISCOVERY.matcher(normalized).matches()) {
            return InformationScoutIntent.profileDiscovery();
        }
        if (TOPIC_MISSING.matcher(normalized).matches()) {
            return InformationScoutIntent.needClarification("你想重点查找哪个主题？");
        }
        if (!ScoutTriggerPolicy.hasExplicitRequest(message)) {
            return InformationScoutIntent.noAction();
        }

        String query = TOPIC_REQUEST_PREFIX.matcher(message).replaceFirst("").trim();
        if (!hasSubstantiveTopic(query)) {
            return InformationScoutIntent.needClarification("你想重点查找哪个主题？");
        }
        return InformationScoutIntent.topicSearch(query);
    }

    private boolean hasSubstantiveTopic(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String withoutRequest = TOPIC_REQUEST_PREFIX.matcher(text).replaceFirst("");
        String residue = GENERIC_TOPIC_TERMS.matcher(withoutRequest).replaceAll("")
                .replaceAll("[\\s的了呢呀吧吗啊和与及、，。！？!?：:]", "");
        if (residue.matches(".*[A-Za-z0-9]{2,}.*")) {
            return true;
        }
        return residue.codePointCount(0, residue.length()) >= 2;
    }
}
