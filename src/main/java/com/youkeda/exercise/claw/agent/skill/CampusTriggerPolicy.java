package com.youkeda.exercise.claw.agent.skill;

import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.regex.Pattern;

/** Personal timetable intent, rather than arbitrary mentions of schools or courses. */
@Component("campusTriggerPolicy")
public class CampusTriggerPolicy implements SkillTriggerPolicy {
    private static final Pattern OUTSIDE_SCOPE = Pattern.compile(
            "(?:推荐|挑选|介绍|购买).{0,12}(?:课程|网课|学校)|(?:课程|网课).{0,8}(?:推荐|哪个好)"
                    + "|(?:讲解|解释|科普).{0,12}(?:知识|原理|课程)|(?:课表|考试|课程)是什么");
    private static final Pattern REQUEST = Pattern.compile(
            "课表|课程表|考试安排|考试时间|考试提醒|上课提醒|课程提醒|停课|调课|补课"
                    + "|(?:今天|明天|后天|本周|下周|周[一二三四五六日天]|\\d{1,2}月\\d{1,2}日|\\d{4}-\\d{2}-\\d{2}).{0,12}(?:什么课|哪些课|有课|上课|没课|考试)"
                    + "|(?:我的|个人).{0,6}(?:课程|考试|课|学校|学期)"
                    + "|(?:导入|查询|查看|修改|删除|取消|添加|安排|登记|记录).{0,12}(?:课程|考试|上课|教室)"
                    + "|(?:绑定|设置).{0,6}(?:学校|学期|开学日期)"
                    + "|(?:课|考试).{0,8}(?:改到|改成|改为|在哪|几点|什么时候|提前提醒)");

    @Override
    public SkillTriggerMatch match(String message, Optional<SkillSession> session) {
        if (message == null || message.isBlank() || OUTSIDE_SCOPE.matcher(message).find()) {
            return SkillTriggerMatch.noMatch();
        }
        if (session.map(value -> "campus".equals(value.activeSkill())).orElse(false)
                && message.matches("(?:确认|确认导入|确认保存|取消|取消导入|明天呢|后天呢|下周呢|周[一二三四五六日天]呢|改到.{1,20}|第.{1,12}节)[。！!？?]?")) {
            return new SkillTriggerMatch(true, 0.7, "campus clarification or confirmation", true);
        }
        if (!REQUEST.matcher(message).find()) return SkillTriggerMatch.noMatch();
        return new SkillTriggerMatch(true, 0.9, "personal timetable or exam management", false);
    }

    public static boolean isOutsideScope(String message) {
        return message != null && OUTSIDE_SCOPE.matcher(message).find();
    }
}
