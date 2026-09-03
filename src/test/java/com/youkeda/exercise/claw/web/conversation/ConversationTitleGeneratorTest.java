package com.youkeda.exercise.claw.web.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationTitleGeneratorTest {

    @Test
    void removesRequestPrefixAndKeepsOnlyFirstSentence() {
        assertEquals("规划一趟周末杭州两日游",
                ConversationTitleGenerator.fromMessage("帮我规划一趟周末杭州两日游。预算 2000 元"));
        assertEquals("咖啡只喝中杯",
                ConversationTitleGenerator.fromMessage("记住：咖啡只喝中杯"));
    }

    @Test
    void createsAttachmentTitlesAndLimitsLengthByCodePoint() {
        assertEquals("分析图片 · 西湖路线", ConversationTitleGenerator.fromAttachmentFileName("西湖路线.png"));
        String title = ConversationTitleGenerator.fromMessage("请你帮我整理这是一段非常非常非常非常非常非常长的需求标题用于测试");
        assertTrue(title.codePointCount(0, title.length()) <= 22);
        assertTrue(title.startsWith("整理"));
        assertTrue(title.endsWith("…"));
    }
}
