package com.youkeda.exercise.claw.feature.scout.context;

import java.util.List;

/**
 * 用户画像
 *
 * 从长期记忆中提取，用于驱动搜索规划和语义匹配
 */
public record UserProfile(
        List<String> interests,
        List<String> currentProjects,
        List<String> techStack,
        List<String> goals,
        String contextSummary
) {

    public String toText() {
        StringBuilder sb = new StringBuilder();
        if (interests != null && !interests.isEmpty()) {
            sb.append("兴趣：").append(String.join("、", interests)).append("\n");
        }
        if (currentProjects != null && !currentProjects.isEmpty()) {
            sb.append("当前项目：").append(String.join("、", currentProjects)).append("\n");
        }
        if (techStack != null && !techStack.isEmpty()) {
            sb.append("技术栈：").append(String.join("、", techStack)).append("\n");
        }
        if (goals != null && !goals.isEmpty()) {
            sb.append("目标：").append(String.join("、", goals)).append("\n");
        }
        return sb.toString();
    }
}
