package com.youkeda.exercise.claw.agent.context;

import com.youkeda.exercise.claw.agent.AgentContext;
import com.youkeda.exercise.claw.agent.memory.ContextStore;
import com.youkeda.exercise.claw.agent.memory.ConversationSummary;
import com.youkeda.exercise.claw.agent.memory.ConversationSummaryService;
import com.youkeda.exercise.claw.agent.memory.ConversationTurn;
import com.youkeda.exercise.claw.agent.memory.Message;
import com.youkeda.exercise.claw.agent.memory.MessageRole;
import com.youkeda.exercise.claw.agent.memory.TurnInitiator;
import com.youkeda.exercise.claw.agent.memory.TurnStatus;
import com.youkeda.exercise.claw.agent.memory.longterm.LongTermMemoryService;
import com.youkeda.exercise.claw.agent.memory.longterm.MemoryItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 1C 等价性测试：DefaultContextBuilder.build 输出
 * 与 MessageHistoryBuilder.buildMessages 语义等价（历史过滤 + 当前消息去重 + 长期记忆注入）。
 */
class DefaultContextBuilderTest {

    private final ContextStore contextStore = mock(ContextStore.class);
    private final LongTermMemoryService longTermMemoryService = mock(LongTermMemoryService.class);

    private DefaultContextBuilder newBuilder() {
        when(longTermMemoryService.recall(anyString())).thenReturn(List.of());
        return new DefaultContextBuilder(contextStore, longTermMemoryService);
    }

    private AgentContext ctx(String message) {
        return new AgentContext().setUserId("user-1").setMessage(message);
    }

    private ConversationTurn turn(String roundId, long seq, Message... msgs) {
        return new ConversationTurn(roundId, seq, TurnStatus.COMPLETED,
                TurnInitiator.USER, List.of(msgs), Instant.now());
    }

    @Test
    void emptyHistoryAddsCurrentMessage() {
        when(contextStore.getTurns(anyInt())).thenReturn(List.of());
        DefaultContextBuilder builder = newBuilder();

        ContextBuilder.Result result = builder.build(ctx("你好"));

        assertEquals(1, result.messages().size());
        assertEquals(MessageRole.USER, result.messages().get(0).role());
        assertEquals("你好", result.messages().get(0).content());
    }

    @Test
    void currentMessageAlreadyInHistoryNotDuplicated() {
        // 真实场景：当前用户消息已在 agent run 前写入历史（作为最近 Turn 的 user）
        when(contextStore.getTurns(anyInt())).thenReturn(List.of(
                turn("r2", 2, new Message("user", "你好")),
                turn("r1", 1, new Message("user", "你好"), new Message("assistant", "你好呀"))));
        DefaultContextBuilder builder = newBuilder();

        ContextBuilder.Result result = builder.build(ctx("你好"));

        // flatten 后末条 user == 当前消息 → 不重复追加
        assertEquals(3, result.messages().size());
        assertEquals(MessageRole.USER, result.messages().get(2).role());
        assertEquals("你好", result.messages().get(2).content());
    }

    @Test
    void continuationRequestFiltersLegacyLimitReply() {
        // 历史：user + assistant 旧上限提示；当前消息"继续"（延续请求）
        when(contextStore.getTurns(anyInt())).thenReturn(List.of(
                turn("r1", 1,
                        new Message("user", "帮我查"),
                        new Message("assistant", "本轮处理步骤已达到上限，请回复\"继续生成\""))));
        DefaultContextBuilder builder = newBuilder();

        ContextBuilder.Result result = builder.build(ctx("继续"));

        // continuation 请求 → 过滤旧上限提示；末条 user 非当前消息 → 追加"继续"
        assertEquals(2, result.messages().size());
        assertFalse(result.messages().stream()
                .anyMatch(m -> m.content() != null && m.content().contains("已达到上限")));
        assertEquals("继续", result.messages().get(result.messages().size() - 1).content());
    }

    @Test
    void longTermMemoryInjectedAtFront() {
        when(contextStore.getTurns(anyInt())).thenReturn(List.of(
                turn("r1", 1, new Message("user", "你好"))));
        MemoryItem memory = MemoryItem.ofAuto(
                com.youkeda.exercise.claw.agent.memory.longterm.MemoryCategory.PREFERENCE,
                "用户喜欢喝美式", 0.8f);
        when(longTermMemoryService.recall(anyString())).thenReturn(List.of(memory));
        when(longTermMemoryService.buildMemoryPrompt(any())).thenReturn("【长期记忆】用户喜欢喝美式");
        DefaultContextBuilder builder = new DefaultContextBuilder(contextStore, longTermMemoryService);

        ContextBuilder.Result result = builder.build(ctx("你好"));

        // 记忆 system 消息在最前
        assertEquals(MessageRole.SYSTEM, result.messages().get(0).role());
        assertTrue(result.messages().get(0).content().contains("用户喜欢喝美式"));
        // 历史 + 当前消息在其后
        assertEquals(2, result.messages().size());
        // 溯源元数据带 LONG_TERM_MEMORY 来源
        assertTrue(result.metadata().sources().stream()
                .anyMatch(ref -> ref.source() == ContextSource.LONG_TERM_MEMORY));
    }

    @Test
    void turnHistoryFlattenedChronologically() {
        // 两个 Turn：最新在前（r2 新、r1 旧）
        when(contextStore.getTurns(anyInt())).thenReturn(List.of(
                turn("r2", 2, new Message("user", "再见")),
                turn("r1", 1, new Message("user", "你好"), new Message("assistant", "你好呀"))));
        DefaultContextBuilder builder = newBuilder();

        ContextBuilder.Result result = builder.build(ctx("还有问题"));

        // 时间正序：r1 的两条在前，r2 在后，再加当前消息
        assertEquals(4, result.messages().size());
        assertEquals("你好", result.messages().get(0).content());
        assertEquals("你好呀", result.messages().get(1).content());
        assertEquals("再见", result.messages().get(2).content());
        assertEquals("还有问题", result.messages().get(3).content());
        // 溯源元数据带最近 Turn 的 roundId
        assertEquals("r2", result.metadata().turnId());
    }

    @Test
    void continuationRequestDetection() {
        DefaultContextBuilder builder = newBuilder();
        assertTrue(builder.isContinuationRequest("继续"));
        assertTrue(builder.isContinuationRequest("继续生成"));
        assertTrue(builder.isContinuationRequest("接着生成"));
        assertFalse(builder.isContinuationRequest("你好"));
        assertFalse(builder.isContinuationRequest(null));
    }

    // ==================== Phase 1D 预算裁剪 ====================

    @Test
    void budgetTrimsOldTurnsButKeepsNewest() {
        // 3 个 Turn，各 6 CJK tokens；预算 12 → 保留最新 2 个
        when(contextStore.getTurns(anyInt())).thenReturn(List.of(
                turn("r3", 3, new Message("user", "一二三四五六")),
                turn("r2", 2, new Message("user", "一二三四五六")),
                turn("r1", 1, new Message("user", "一二三四五六"))));
        DefaultContextBuilder builder = new DefaultContextBuilder(
                contextStore, longTermMemoryService,
                new HeuristicTokenEstimator(), 12);

        ContextBuilder.Result result = builder.build(ctx("现在的问题"));

        // 保留 r3 + r2 的消息 + 当前消息 = 3 条
        assertEquals(3, result.messages().size());
        assertEquals("一二三四五六", result.messages().get(0).content());
        assertEquals("一二三四五六", result.messages().get(1).content());
        assertEquals("现在的问题", result.messages().get(2).content());
        // 元数据 turnId = 最新保留 Turn
        assertEquals("r3", result.metadata().turnId());
        // Result 预算有界（非 unbounded）
        assertTrue(result.budget().maxTokens() > 0);
    }

    @Test
    void budgetKeepsNewestTurnEvenWhenOver() {
        // 最新 Turn 巨大（超预算）→ 仍保留
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 300; i++) big.append("巨");
        when(contextStore.getTurns(anyInt())).thenReturn(List.of(
                turn("r2", 2, new Message("user", big.toString())),
                turn("r1", 1, new Message("user", "旧对话"))));
        DefaultContextBuilder builder = new DefaultContextBuilder(
                contextStore, longTermMemoryService,
                new HeuristicTokenEstimator(), 10);

        ContextBuilder.Result result = builder.build(ctx("新消息"));

        // 只保留最新 Turn + 当前消息
        assertEquals(2, result.messages().size());
        assertTrue(result.messages().get(0).content().length() > 100);
        assertEquals("新消息", result.messages().get(1).content());
        assertEquals("r2", result.metadata().turnId());
    }

    // ==================== Phase 3 对话摘要 ====================

    @Test
    void summaryInjectedAtFrontWithCoveredUntilSeq() {
        ConversationSummaryService summaryService = mock(ConversationSummaryService.class);
        when(summaryService.getSummary())
                .thenReturn(new ConversationSummary("用户喜欢旅游，偏好低价酒店", 12));
        when(contextStore.getTurns(anyInt())).thenReturn(List.of(
                turn("r13", 13, new Message("user", "继续"))));
        DefaultContextBuilder builder = new DefaultContextBuilder(
                contextStore, longTermMemoryService, summaryService,
                new HeuristicTokenEstimator(), 0);

        ContextBuilder.Result result = builder.build(ctx("继续"));

        // 摘要 system 消息在最前
        assertEquals(MessageRole.SYSTEM, result.messages().get(0).role());
        assertTrue(result.messages().get(0).content().contains("用户喜欢旅游"));
        assertTrue(result.messages().get(0).content().contains("12"));
        // coveredUntilTurn 填充
        assertEquals(12, result.coveredUntilTurn());
        // 溯源元数据带 SUMMARY 来源
        assertTrue(result.metadata().sources().stream()
                .anyMatch(ref -> ref.source() == ContextSource.SUMMARY));
    }

    @Test
    void noSummaryWhenServiceNull() {
        // 默认 2 参构造（summaryService=null）→ 不注入摘要
        when(contextStore.getTurns(anyInt())).thenReturn(List.of(
                turn("r1", 1, new Message("user", "你好"))));
        DefaultContextBuilder builder = newBuilder();

        ContextBuilder.Result result = builder.build(ctx("你好"));

        assertEquals(0, result.coveredUntilTurn());
        assertFalse(result.messages().stream().anyMatch(m -> m.role() == MessageRole.SYSTEM));
    }

    @Test
    void summaryZeroCoveredSeqNotInjected() {
        ConversationSummaryService summaryService = mock(ConversationSummaryService.class);
        when(summaryService.getSummary()).thenReturn(new ConversationSummary("内容", 0));
        when(contextStore.getTurns(anyInt())).thenReturn(List.of(
                turn("r1", 1, new Message("user", "你好"))));
        DefaultContextBuilder builder = new DefaultContextBuilder(
                contextStore, longTermMemoryService, summaryService,
                new HeuristicTokenEstimator(), 0);

        ContextBuilder.Result result = builder.build(ctx("你好"));

        // coveredUntilSeq=0 表示摘要未启用 → 不注入
        assertEquals(0, result.coveredUntilTurn());
        assertFalse(result.messages().stream().anyMatch(m -> m.role() == MessageRole.SYSTEM));
    }

    @Test
    void usageTrackerReceivesUsedTokens() {
        // L1 埋点：每次 build 把实际发送 token 记入 tracker
        when(contextStore.getTurns(anyInt())).thenReturn(List.of(
                turn("r2", 2, new Message("user", "今天天气怎么样")),
                turn("r1", 1, new Message("user", "你好"), new Message("assistant", "你好，有什么可以帮你？"))));
        ContextUsageTracker tracker = mock(ContextUsageTracker.class);
        DefaultContextBuilder builder = new DefaultContextBuilder(
                contextStore, longTermMemoryService, null,
                new HeuristicTokenEstimator(), 0, tracker);

        builder.build(ctx("今天天气怎么样"));

        verify(tracker).recordBuild(anyInt());
    }
}
