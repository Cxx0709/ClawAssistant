package com.youkeda.exercise.claw.agent.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话上下文存储
 *
 * 职责：存取用户对话历史，为 LLM 多轮对话提供记忆能力。
 * 每条 Message 可携带可选的媒体参数（CDN 下载参数、URL、TTS 音频），
 * 不再需要单独的 setLastImage / addVoiceMedia 等碎片方法。
 */
public interface ContextStore {

    /**
     * 获取最近 maxMessages 条历史消息（按时间正序）
     */
    List<Message> getHistory(int maxMessages);

    List<Message> getHistory(String userId, int maxMessages);

    /**
     * 追加一条纯文本消息
     */
    void append(String role, String content);

    /**
     * 追加一条带媒体附件的消息
     *
     * @param mediaEncryptParam 媒体 CDN 加密参数（无则 null）
     * @param mediaAesKey       媒体 CDN 解密密钥（无则 null）
     * @param mediaUrl          媒体 URL（无则 null）
     */
    void append(String role, String content,
                String mediaEncryptParam, String mediaAesKey,
                String mediaUrl);

    /**
     * 追加一条完整的 Message（保留 toolCallId / toolName / reasoningContent）。
     * <p>用于持久化工具调用与工具结果，使下一轮对话的 LLM 能看到真实的工具执行记录，
     * 避免因历史中缺失工具证据而误判上一轮结果为编造。
     */
    void append(Message message);

    void append(String userId, Message message);

    /**
     * 找到最近一条 content 以 prefix 开头的消息（反向扫描）
     */
    Message findLastByPrefix(String contentPrefix);

    /**
     * 找到所有 content 以 prefix 开头的消息（正序）
     */
    List<Message> findAllByPrefix(String contentPrefix);

    /**
     * 读取最近 maxTurns 个 Turn（按 seq 倒序，最新在前）。
     *
     * <p>过滤 INCOMPLETE Turn（不进窗口）。未设置 round_id 的旧行退化为逐条成 Turn。
     * 单用户模式下自动解析当前活跃 userId。
     */
    List<ConversationTurn> getTurns(int maxTurns);

    List<ConversationTurn> getTurns(String userId, int maxTurns);

    /**
     * 开启一个新 Turn 并写入其首条（用户）消息。
     *
     * @param roundId      由调用方生成的轮次 ID（UUID）
     * @param initiator    发起方（USER / SYSTEM）
     * @param firstMessage 首条消息（通常为用户消息）
     * @return 分配的 seq（每用户单调递增）
     */
    long beginTurn(String roundId, TurnInitiator initiator, Message firstMessage);

    /**
     * 向指定 Turn 追加消息（同一轮次内保持 round_id 不变，seq 递增）。
     */
    void appendToTurn(String roundId, Message message);

    /**
     * 关闭 Turn（标记 COMPLETED）。
     */
    void closeTurn(String roundId);

    /**
     * 标记 Turn 为 INCOMPLETE（异常中断）。
     */
    void markTurnIncomplete(String roundId);

    /**
     * 读取最近 maxTurns 个 Turn 的消息（时间正序）。
     *
     * <p>ADR §7.3/1E：供非 agent 主路径的 LLM 消费者（ChatService、FileGenerationService）
     * 使用 turn-aware 读取——Turn 为原子单位，窗口不切破工具轮次，不会产生孤立 tool。
     */
    default List<Message> getRecentMessages(int maxTurns) {
        List<ConversationTurn> turns = getTurns(maxTurns);
        List<Message> messages = new ArrayList<>();
        for (int i = turns.size() - 1; i >= 0; i--) {
            messages.addAll(turns.get(i).messages());
        }
        return messages;
    }

    /**
     * 清除全部上下文
     */
    void clear();

    void clear(String userId);
}
