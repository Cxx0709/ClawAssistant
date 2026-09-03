package com.youkeda.exercise.claw.agent.memory;

import com.youkeda.exercise.claw.agent.context.ContextUsageTracker;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 归档判定与增量合并测试。
 */
class ConversationSummaryServiceTest {

    private ConversationSummaryStore summaryStore;
    private ContextStore contextStore;
    private LLMClient llmClient;
    private ConversationSummaryService service;

    /** 同步执行（测试用，避免异步线程竞态）。 */
    private final Executor syncExecutor = Runnable::run;

    @BeforeEach
    void setUp() {
        summaryStore = mock(ConversationSummaryStore.class);
        contextStore = mock(ContextStore.class);
        llmClient = mock(LLMClient.class);
        service = new ConversationSummaryService(summaryStore, contextStore, llmClient, syncExecutor);
    }

    private ConversationTurn turn(long seq, Message... msgs) {
        return new ConversationTurn("round-" + seq, seq, TurnStatus.COMPLETED,
                TurnInitiator.USER, List.of(msgs), Instant.now());
    }

    private Message msg(String role, String content) {
        return new Message(role, content);
    }

    /** 生成 N 个 Turn（最新在前），每个一条 user+assistant。 */
    private List<ConversationTurn> turnsNewestFirst(int count) {
        List<ConversationTurn> turns = new ArrayList<>();
        for (long seq = count; seq >= 1; seq--) {
            turns.add(turn(seq, msg("user", "消息" + seq), msg("assistant", "回复" + seq)));
        }
        return turns;
    }

    @Test
    void noArchiveWhenWithinWindow() {
        // 只有 10 个 Turn（= 窗口），不归档
        when(contextStore.getTurns(anyInt())).thenReturn(turnsNewestFirst(10));

        service.archiveIfNeeded();

        verify(summaryStore, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void archiveWhenWindowExceededByEnough() {
        // 15 个 Turn，窗口 10，窗口外 5 个（seq 1-5 未覆盖）→ 归档
        when(contextStore.getTurns(anyInt())).thenReturn(turnsNewestFirst(15));
        when(summaryStore.get()).thenReturn(null);
        when(llmClient.chatWithSystemPrompt(anyString(), anyString()))
                .thenReturn("合并后的摘要");

        service.archiveIfNeeded();

        // 覆盖到 seq 5（窗口外的最大未覆盖 seq），5 个 Turn
        org.mockito.ArgumentCaptor<ConversationSummary> captor =
                org.mockito.ArgumentCaptor.forClass(ConversationSummary.class);
        verify(summaryStore).save(captor.capture());
        assertEquals("合并后的摘要", captor.getValue().text());
        assertEquals(5, captor.getValue().coveredUntilSeq());
    }

    @Test
    void usageTrackerReceivesCompressionMetrics() {
        // L1 埋点：归档成功后把「原文 token / 摘要 token」记入 tracker
        when(contextStore.getTurns(anyInt())).thenReturn(turnsNewestFirst(15));
        when(summaryStore.get()).thenReturn(null);
        when(llmClient.chatWithSystemPrompt(anyString(), anyString()))
                .thenReturn("合并后的摘要");
        ContextUsageTracker tracker = mock(ContextUsageTracker.class);
        service.setUsageTracker(tracker);

        service.archiveIfNeeded();

        verify(tracker).recordSummary(anyInt(), anyInt());
    }

    @Test
    void notEnoughUnarchivedTurnsSkips() {
        // 12 个 Turn，窗口 10，窗口外 2 个（seq 1-2）< ARCHIVE_BATCH=5 → 不归档
        when(contextStore.getTurns(anyInt())).thenReturn(turnsNewestFirst(12));
        when(summaryStore.get()).thenReturn(null);

        service.archiveIfNeeded();

        verify(summaryStore, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void alreadyCoveredTurnsNotReArchived() {
        // 15 个 Turn，但 summary 已覆盖到 seq 5 → 窗口外 1-5 全已覆盖，不再归档
        when(contextStore.getTurns(anyInt())).thenReturn(turnsNewestFirst(15));
        when(summaryStore.get()).thenReturn(new ConversationSummary("旧摘要", 5));

        service.archiveIfNeeded();

        verify(summaryStore, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void incrementalMergeUsesPriorSummary() {
        // 已有摘要覆盖到 seq 3；15 个 Turn 窗口外 1-5，未覆盖的 4-5（2 个）< ARCHIVE_BATCH=5 → 不归档
        // 增量语义：已覆盖的 1-3 不计入新批次
        when(contextStore.getTurns(anyInt())).thenReturn(turnsNewestFirst(15));
        when(summaryStore.get()).thenReturn(new ConversationSummary("用户喜欢旅游", 3));

        service.archiveIfNeeded();

        verify(summaryStore, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void incrementalMergeAdvancesAnchorWhenBatchReached() {
        // 已有摘要覆盖到 seq 1；15 个 Turn 窗口外 1-5，未覆盖的 2-5（4 个）< 5 → 不归档
        when(contextStore.getTurns(anyInt())).thenReturn(turnsNewestFirst(15));
        when(summaryStore.get()).thenReturn(new ConversationSummary("旧摘要", 1));

        service.archiveIfNeeded();

        verify(summaryStore, never()).save(org.mockito.ArgumentMatchers.any());

        // 扩展到 16 个 Turn：窗口外 1-6，未覆盖的 2-6（5 个）达标 → 合并到 seq 6
        when(contextStore.getTurns(anyInt())).thenReturn(turnsNewestFirst(16));
        when(llmClient.chatWithSystemPrompt(anyString(), anyString()))
                .thenReturn("合并摘要");

        service.archiveIfNeeded();

        org.mockito.ArgumentCaptor<ConversationSummary> captor =
                org.mockito.ArgumentCaptor.forClass(ConversationSummary.class);
        verify(summaryStore).save(captor.capture());
        assertEquals(6, captor.getValue().coveredUntilSeq());
    }

    @Test
    void asyncArchiveRunsThroughExecutor() {
        // asyncArchiveIfNeeded 应把归档任务提交给 executor
        when(contextStore.getTurns(anyInt())).thenReturn(turnsNewestFirst(15));
        when(summaryStore.get()).thenReturn(null);
        when(llmClient.chatWithSystemPrompt(anyString(), anyString())).thenReturn("摘要");

        service.asyncArchiveIfNeeded();

        // syncExecutor 直接同步跑 → 归档已完成
        org.mockito.ArgumentCaptor<ConversationSummary> captor =
                org.mockito.ArgumentCaptor.forClass(ConversationSummary.class);
        verify(summaryStore).save(captor.capture());
        assertNotNull(captor.getValue());
    }

    @Test
    void llmFailureSkipsArchive() {
        when(contextStore.getTurns(anyInt())).thenReturn(turnsNewestFirst(15));
        when(summaryStore.get()).thenReturn(null);
        when(llmClient.chatWithSystemPrompt(anyString(), anyString())).thenReturn(null);

        service.archiveIfNeeded();

        verify(summaryStore, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void getSummaryDelegatesToStore() {
        ConversationSummary s = new ConversationSummary("摘要", 7);
        when(summaryStore.get()).thenReturn(s);
        assertEquals(s, service.getSummary());
    }
}
