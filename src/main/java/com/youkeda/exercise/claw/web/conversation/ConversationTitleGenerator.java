package com.youkeda.exercise.claw.web.conversation;

import java.util.Locale;

/** 无需额外调用模型的稳定标题生成器。 */
public final class ConversationTitleGenerator {

    private static final int MAX_CODE_POINTS = 22;
    private static final String REQUEST_PREFIX = "^(?:可以帮我|能不能帮我|能帮我|我想让你|想请你|麻烦你|麻烦|请你|请|帮我|帮忙|给我|替我)"
            + "(?:一下|下)?[\\s，,：:、-]*";

    private ConversationTitleGenerator() {
    }

    public static String fromMessage(String message) {
        String title = clean(message);
        if (title.isBlank()) return "新对话";
        for (int i = 0; i < 3; i++) {
            String stripped = title.replaceFirst(REQUEST_PREFIX, "");
            if (stripped.equals(title)) break;
            title = stripped;
        }
        title = title.replaceFirst("^(?:关于|有关)[\\s，,：:、-]*", "");
        title = title.replaceFirst("^(?:记住|记录一下)[\\s，,：:、-]+", "");
        String[] sentences = title.split("[。！？!?\\r\\n]", 2);
        title = clean(sentences[0]);
        title = title.replaceAll("^[\"“”'‘’]+|[\"“”'‘’]+$", "");
        if (title.isBlank()) return "新对话";
        return truncateCodePoints(title, MAX_CODE_POINTS);
    }

    public static String fromAttachmentFileName(String fileName) {
        String name = clean(fileName);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = fromMessage(name);
        if (name.equals("新对话")) return "附件对话";
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String action = lower.matches(".*\\.(png|jpe?g|gif|webp|bmp)$") ? "分析图片 · "
                : lower.matches(".*\\.(mp3|wav|m4a|aac|ogg)$") ? "整理音频 · " : "查看文件 · ";
        return truncateCodePoints(action + name, MAX_CODE_POINTS);
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replaceAll("[`*_#>]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String truncateCodePoints(String value, int max) {
        int count = value.codePointCount(0, value.length());
        if (count <= max) return value;
        int end = value.offsetByCodePoints(0, max - 1);
        return value.substring(0, end) + "…";
    }
}
