package com.youkeda.exercise.claw.agent.memory;

import com.youkeda.exercise.claw.agent.context.ContextUsageTracker;
import com.youkeda.exercise.claw.agent.context.HeuristicTokenEstimator;
import com.youkeda.exercise.claw.agent.context.TokenEstimator;
import com.youkeda.exercise.claw.ai.llm.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 对话摘要服务（ADR §9/Phase 3）。
 *
 * <p>把窗口外的早期对话增量合并成摘要（以 coveredUntilSeq 标记已覆盖到的轮次）。
 * 触发由 {@code ContextBuilder} 在每次组装时调用 {@link #asyncArchiveIfNeeded()}，
 * 归档判定有阈值门（{@link #ARCHIVE_BATCH}），多数时候零成本。
 *
 * <p>增量模型：上下文窗口保留最近 {@link #KEEP_TURNS} 个 Turn；
 * 当窗口外累积的未覆盖 Turn ≥ {@link #ARCHIVE_BATCH}，把这一批（连同旧摘要）
 * 交给 LLM 生成合并摘要，coveredUntilSeq 推进到该批最大 seq。
 */
@Component
public class ConversationSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummaryService.class);

    /** 上下文窗口保留的最近 Turn 数（与 DefaultContextBuilder 一致）。 */
    static final int KEEP_TURNS = 10;
    /** 一次归档的最小未覆盖 Turn 数（低于此不值得生成摘要）。 */
    static final int ARCHIVE_BATCH = 5;
    /** 读取评估所需的 Turn 数 = 窗口 + 一批。 */
    private static final int EVAL_TURNS = KEEP_TURNS + ARCHIVE_BATCH;

    private static final String SUMMARY_PROMPT = """
            你是对话摘要助手。把「旧摘要」与「新对话」合并成一份更完整的新摘要。
            保留：用户的偏好与目标、关键事实、未完成的事项、提到的名字/时间/地点、双方达成的结论。
            省略：寒暄、无关细节、重复内容。
            直接输出合并后的摘要，不要加前缀。

            旧摘要：
            %s

            新对话（从旧到新）：
            %s
            """;

    private final ConversationSummaryStore summaryStore;
    private final ContextStore contextStore;
    private final LLMClient llmClient;
    private final Executor taskExecutor;
    /** L1 埋点：摘要压缩率统计（可 null = 不记录）。由 ReActAgentExecutor 装配共享实例。 */
    private ContextUsageTracker usageTracker;
    /** 字符启发式 token 估算（无状态，仅用于埋点，不参与核心逻辑）。 */
    private final TokenEstimator tokenEstimator = new HeuristicTokenEstimator();

    public ConversationSummaryService(ConversationSummaryStore summaryStore,
                                      ContextStore contextStore,
                                      LLMClient llmClient,
                                      @Qualifier("memoryTaskExecutor") Executor taskExecutor) {
        this.summaryStore = summaryStore;
        this.contextStore = contextStore;
        this.llmClient = llmClient;
        this.taskExecutor = taskExecutor;
    }

    /** 获取当前摘要（无则 null）。 */
    public ConversationSummary getSummary() {
        return summaryStore.get();
    }

    /** 装配共享的 L1 埋点聚合器（与 DefaultContextBuilder 同一实例）。 */
    public void setUsageTracker(ContextUsageTracker usageTracker) {
        this.usageTracker = usageTracker;
    }

    /** 异步触发归档判定（不阻塞调用方）。 */
    public void asyncArchiveIfNeeded() {
        try {
            taskExecutor.execute(this::archiveIfNeeded);
        } catch (RejectedExecutionException e) {
            log.warn("摘要任务队列已满，本轮跳过");
        }
    }

    /**
     * 归档判定 + 生成（同步核心逻辑，供异步包装与测试）。
     * 幂等：仅当窗口外存在足够多未覆盖 Turn 时才生成。
     */
    public void archiveIfNeeded() {
        try {
            List<ConversationTurn> turns = contextStore.getTurns(EVAL_TURNS);
            if (turns.size() <= KEEP_TURNS) {
                return; // 没超出窗口，无需归档
            }

            ConversationSummary summary = summaryStore.get();
            long coveredSeq = summary != null ? summary.coveredUntilSeq() : 0L;

            // 窗口外的旧 Turn = 最新在前列表里 seq 最小的 (size - KEEP_TURNS) 个
            List<ConversationTurn> windowedOut = new ArrayList<>(
                    turns.subList(KEEP_TURNS, turns.size()));
            // 取其中尚未覆盖的（seq > coveredSeq），按 seq 升序
            List<ConversationTurn> unarchived = windowedOut.stream()
                    .filter(t -> t.seq() > coveredSeq)
                    .sorted(Comparator.comparingLong(ConversationTurn::seq))
                    .toList();

            if (unarchived.size() < ARCHIVE_BATCH) {
                return; // 未覆盖的太少，暂不归档
            }

            // 生成合并摘要
            String oldText = summary != null ? summary.text() : "（无）";
            String batchText = formatTurns(unarchived);
            int rawTokens = tokenEstimator.estimate(batchText);
            String merged = llmClient.chatWithSystemPrompt(
                    "你是对话摘要助手。请根据系统提示合并对话摘要。",
                    String.format(SUMMARY_PROMPT, oldText, batchText));
            if (merged == null || merged.isBlank()) {
                log.warn("摘要生成返回空，跳过本轮归档");
                return;
            }

            long newCoveredSeq = unarchived.get(unarchived.size() - 1).seq();
            summaryStore.save(new ConversationSummary(merged.strip(), newCoveredSeq));
            // L1 埋点：原文 token → 摘要 token 的压缩率
            int mergedTokens = tokenEstimator.estimate(merged);
            if (usageTracker != null) {
                usageTracker.recordSummary(rawTokens, mergedTokens);
            }
            log.info("对话摘要归档完成 | 覆盖到 seq={} | turns={} | summaryLen={} | tokens={}→{}",
                    newCoveredSeq, unarchived.size(), merged.length(), rawTokens, mergedTokens);
        } catch (Exception e) {
            log.warn("对话摘要归档失败 | error={}", e.getMessage());
        }
    }

    /** 把 Turn 列表格式化成「从旧到新」的对话文本（user/assistant 文本）。 */
    private String formatTurns(List<ConversationTurn> turns) {
        StringBuilder sb = new StringBuilder();
        for (ConversationTurn turn : turns) {
            for (Message m : turn.messages()) {
                sb.append('[').append(m.role().name().toLowerCase()).append("] ")
                        .append(m.content() != null ? m.content() : "")
                        .append('\n');
            }
        }
        return sb.toString().strip();
    }
}
