package com.youkeda.exercise.claw.ai.llm;

import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.agent.memory.ContextStore;
import com.youkeda.exercise.claw.agent.memory.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 聊天服务
 *
 * <p>封装无工具调用的单轮对话逻辑。当前主要用于 {@code FileHandler} 文件分析场景，
 * 通过 {@link LLMClient#chatWithSystemPrompt} 完成模型交互。
 *
 * <p>多轮对话 + tool-calling 的主链路见 {@code ReActAgentExecutor}。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 每次请求携带的最大历史 Turn 数 */
    private static final int MAX_TURNS = 10;

    private final LLMClient llmClient;
    private final ContextStore contextStore;

    public ChatService(LLMClient llmClient, ContextStore contextStore) {
        this.llmClient = llmClient;
        this.contextStore = contextStore;
    }

    /**
     * 生成对话回复（带上下文记忆）
     *
     * @param message 用户消息
     * @return 模型回复，失败时返回 null
     */
    public String chat(String message) {
        log.info("ChatService 开始处理 | text={}", message);

        try {
            // 1. 获取历史上下文（turn-aware 读取，ADR 1E：窗口不切破工具轮次）
            List<Message> history = contextStore.getRecentMessages(MAX_TURNS);
            log.debug("获取历史消息 | historySize={}", history.size());

            // 2. 调用 LLM（带历史）
            String reply = llmClient.chat(message, history);
            if (reply == null || reply.isEmpty()) {
                log.warn("ChatService 回复为空");
                return null;
            }

            // 3. 保存回复到上下文（用户消息已由调用入口统一存储）
            contextStore.append("assistant", reply);

            log.info("ChatService 处理完成");
            return reply;
        } catch (Exception e) {
            log.error("ChatService 处理异常 | error={}", e.getMessage());
            return null;
        }
    }
}
