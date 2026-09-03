package com.youkeda.exercise.claw.agent;

import com.youkeda.exercise.claw.ai.llm.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 快速对话分类器。
 *
 * <p>从 {@code ReActAgentExecutor} 拆出：用 LLM 判断用户消息是否需要调用工具。
 * 非 Spring bean，由 {@code ReActAgentExecutor} 构造时用已有依赖创建。
 */
public class SimpleChatClassifier {

    private static final Logger log = LoggerFactory.getLogger(SimpleChatClassifier.class);

    private final LLMClient llmClient;

    private static final String[] CONVERSATION_HINTS = {
            "聊聊", "聊天", "陪我", "心情", "烦", "难过", "不开心", "焦虑",
            "压力", "孤独", "郁闷", "委屈", "生气", "想哭", "心累"
    };

    private static final String[] TOOL_HINTS = {
            "查", "搜索", "天气", "地图", "路线", "生成", "文件", "图片", "语音",
            "提醒", "定时", "课表", "考试", "打车", "旅游", "追番", "订阅", "记住"
    };

    public SimpleChatClassifier(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 快速判断用户消息是否需要调用工具。
     */
    public boolean isSimpleChat(String userMessage) {
        if (userMessage == null) return false;
        String normalized = userMessage.trim();
        if (normalized.length() <= 3) return false;
        if (containsAny(normalized, CONVERSATION_HINTS)
                && !containsAny(normalized, TOOL_HINTS)) {
            return true;
        }

        String prompt = "你是一个分类器。判断用户消息是否需要调用工具才能完整回答。\n"
                + "需要工具：查天气、查地图/地点/路线、查时间/日期/节假日、搜索网页、"
                + "生成图片、生成文件/文档、语音合成、交通推荐、打车、预算计算、"
                + "查课表/今天课表/导入课表/课程信息/考试安排/考试提醒、"
                + "设置提醒/定时提醒/自定义提醒/创建提醒/管理提醒、"
                + "动漫/番剧推荐、追番/订阅番剧/查询播出时间、"
                + "旅游/出游规划、持续关注/订阅/跟踪某类信息、管理记忆、操作文件。\n"
                + "不需要工具：纯粹的聊天、问答、解释、翻译、写作、闲聊、感谢。\n"
                + "判断规则：只要用户消息可能触发上述任一工具场景（包括提及\"推荐番剧\""
                + "\"追番\"\"旅游规划\"\"持续关注\"等），就返回 NEED_TOOLS。\n"
                + "如果用户消息很短（如\"好\"\"可以\"\"继续\"），可能是在回应之前提出的方案，"
                + "需要让工具系统处理，返回 NEED_TOOLS。\n"
                + "不确定时返回 NEED_TOOLS。\n"
                + "只返回一个词：NEED_TOOLS 或 CHAT_ONLY。";

        String result = llmClient.chatWithSystemPrompt(prompt, normalized);
        return "CHAT_ONLY".equals(result != null ? result.trim() : "");
    }

    private static boolean containsAny(String message, String[] hints) {
        for (String hint : hints) {
            if (message.contains(hint)) return true;
        }
        return false;
    }
}
