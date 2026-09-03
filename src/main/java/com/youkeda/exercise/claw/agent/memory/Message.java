package com.youkeda.exercise.claw.agent.memory;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 单条对话消息记录
 *
 * 媒体数据（语音 CDN 参数、图片 URL）嵌入 Message，统一存入队列。
 * AI 语音回复不存 MP3 字节——文字已在 content 中，需要时重新 TTS 即可；
 * 用户语音的 CDN 参数在 mediaEncryptParam/mediaAesKey 中，需要时重新下载即可。
 *
 * <p>支持三种角色：
 * <ul>
 *   <li>{@code "user"} — 用户消息</li>
 *   <li>{@code "assistant"} — 助手回复，或带 {@code tool_calls} 的中间消息</li>
 *   <li>{@code "tool"} — 工具调用结果</li>
 * </ul>
 *
 * @param role              角色（枚举：user / assistant / tool / system）
 * @param content           消息文本（展示给 LLM 的内容）；tool_call 消息时存 arguments JSON
 * @param mediaEncryptParam 媒体 CDN 加密参数（语音/图片消息时有值）
 * @param mediaAesKey       媒体 CDN 解密密钥（语音/图片消息时有值）
 * @param mediaUrl          媒体 URL（图片时有值）
 * @param toolCallId        工具调用 ID（assistant 带 tool_calls 时 / tool 角色时使用）
 * @param toolName          函数名（assistant 带 tool_calls 时使用）
 */
public record Message(MessageRole role, String content,
                       String mediaEncryptParam, String mediaAesKey,
                       String mediaUrl,
                       String toolCallId, String toolName,
                       String reasoningContent) {

    /** 构造带 toolCallId 和 toolName 但不含 reasoningContent 的消息。 */
    public Message(String role, String content,
                   String mediaEncryptParam, String mediaAesKey,
                   String mediaUrl,
                   String toolCallId, String toolName) {
        this(MessageRole.fromString(role), content, mediaEncryptParam, mediaAesKey, mediaUrl,
                toolCallId, toolName, null);
    }

    /** 纯文本消息 */
    public Message(String role, String content) {
        this(MessageRole.fromString(role), content, null, null, null, null, null, null);
    }

    /** 带媒体附件的消息 */
    public Message(String role, String content,
                   String mediaEncryptParam, String mediaAesKey,
                   String mediaUrl) {
        this(MessageRole.fromString(role), content, mediaEncryptParam, mediaAesKey, mediaUrl, null, null, null);
    }

    /** 是否有媒体 CDN 参数 */
    public boolean hasMedia() {
        return mediaEncryptParam != null && mediaAesKey != null;
    }

    /** 是否为带 tool_calls 的 assistant 消息 */
    @JsonIgnore
    public boolean isToolCall() {
        return role == MessageRole.ASSISTANT && toolCallId != null;
    }
}
