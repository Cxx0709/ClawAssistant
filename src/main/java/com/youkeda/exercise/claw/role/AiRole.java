package com.youkeda.exercise.claw.role;

/**
 * AI 角色定义。用户可以创建自定义角色（如奶奶、英语老师、偶像等），
 * 每个角色有独立的人设、背景故事、说话风格和记忆空间。
 */
public record AiRole(
        String id,
        String userId,
        String name,
        String avatar,
        String personality,
        String background,
        String speakingStyle,
        String catchphrase,
        String voiceAudioUrl,
        String voiceId,
        long createdAt,
        long updatedAt
) {
    /**
     * 构建该角色的 system prompt 片段，注入到对话开头，让大模型用角色口吻回复。
     */
    public String toSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("--- 角色设定 ---\n\n");
        sb.append("你现在扮演的角色是：").append(name).append("\n\n");

        if (personality != null && !personality.isBlank()) {
            sb.append("【性格】").append(personality).append("\n");
        }
        if (background != null && !background.isBlank()) {
            sb.append("【背景故事】").append(background).append("\n");
        }
        if (speakingStyle != null && !speakingStyle.isBlank()) {
            sb.append("【说话风格】").append(speakingStyle).append("\n");
        }
        if (catchphrase != null && !catchphrase.isBlank()) {
            sb.append("【口头禅】").append(catchphrase).append("（对话中自然地使用）\n");
        }

        sb.append("\n【重要规则】\n");
        sb.append("1. 你就是").append(name).append("，不是AI助手，绝对不要暴露自己是人工智能或大模型。\n");
        sb.append("2. 所有回复都要用").append(name).append("的语气和风格，保持角色一致性。\n");
        sb.append("3. 你可以帮用户做很多事（查天气、设提醒、发邮件、生成图片等），但要用角色的生活化语言表达结果，不要说\"调用工具\"、\"API\"、\"定时任务已创建\"这类技术词汇。\n");
        sb.append("4. 记住用户告诉你的关于他自己的事，下次对话时自然地提及。\n");
        sb.append("5. 如果用户问你是谁，你就说你是").append(name).append("，不要说自己是AI。\n");
        sb.append("\n--- 角色设定结束 ---\n\n");
        return sb.toString();
    }
}
