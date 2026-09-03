package com.youkeda.exercise.claw.agent.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * L1 上下文占用埋点聚合器（观测用，非核心逻辑）。
 *
 * <p>聚合两类诚实指标：
 * <ul>
 *   <li><b>发送量受控性</b>：{@link #recordBuild} 记录每次上下文组装实际发送的 token，
 *       汇总得到平均值——摘要 + 窗口机制生效时，平均值应保持平稳而非随轮次线性增长。</li>
 *   <li><b>摘要压缩率</b>：{@link #recordSummary} 记录「被合并 Turn 原文 vs 合并摘要」，
 *       压缩率 + 累计避免重发的 token 总量，量化「早期轮次不再全文重读」的收益。</li>
 * </ul>
 *
 * <p>内存聚合 + 周期性 log.info 汇总，跑几天真实使用后即可从日志读出可信平均值。
 * 线程安全（synchronized），摘要生成在异步线程。
 */
public class ContextUsageTracker {

    private static final Logger log = LoggerFactory.getLogger(ContextUsageTracker.class);

    /** 每 N 次 build 打一次汇总日志；≤0 关闭周期日志。 */
    private final long reportEvery;

    private long buildCount;
    private long totalUsedTokens;
    private long summaryCount;
    private long totalRawTokens;
    private long totalSummaryTokens;

    public ContextUsageTracker(long reportEvery) {
        this.reportEvery = reportEvery;
    }

    /** 记录一次上下文组装：used=实际发送 token。 */
    public synchronized void recordBuild(int usedTokens) {
        buildCount++;
        totalUsedTokens += usedTokens;
        if (reportEvery > 0 && buildCount % reportEvery == 0) {
            Snapshot s = snapshot();
            log.info("ContextUsage 汇总 | builds={} | 平均每次发送={} tokens | 摘要归档={}次 | "
                            + "累计原文 {} → 摘要 {} tokens | 压缩 {}% | 累计避免重发 {} tokens",
                    s.buildCount(), Math.round(s.averageUsedTokens()),
                    s.summaryCount(), s.totalRawTokens(), s.totalSummaryTokens(),
                    round1(s.avgSummaryCompressionPercent()), s.totalAvoidedTokens());
        }
    }

    /** 记录一次摘要归档：raw=被合并原文 token，merged=摘要 token。 */
    public synchronized void recordSummary(int rawTokens, int mergedTokens) {
        summaryCount++;
        totalRawTokens += rawTokens;
        totalSummaryTokens += mergedTokens;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(buildCount, totalUsedTokens, summaryCount, totalRawTokens, totalSummaryTokens);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** 不可变快照，供日志与测试读取。 */
    public record Snapshot(long buildCount, long totalUsedTokens,
                           long summaryCount, long totalRawTokens, long totalSummaryTokens) {

        /** 平均每次上下文组装发送的 token（受控性指标）。 */
        public double averageUsedTokens() {
            return buildCount == 0 ? 0 : (double) totalUsedTokens / buildCount;
        }

        /** 平均摘要压缩率（原文 vs 摘要，值越大压缩越多）。 */
        public double avgSummaryCompressionPercent() {
            if (summaryCount == 0 || totalRawTokens == 0) return 0;
            return (1 - (double) totalSummaryTokens / totalRawTokens) * 100;
        }

        /** 累计避免重发的 token 总量 = 被归档的原文总 token。 */
        public long totalAvoidedTokens() {
            return totalRawTokens;
        }
    }
}
