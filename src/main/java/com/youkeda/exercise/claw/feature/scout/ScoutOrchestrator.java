package com.youkeda.exercise.claw.feature.scout;

import com.youkeda.exercise.claw.feature.scout.collector.CollectorRegistry;
import com.youkeda.exercise.claw.feature.scout.context.UserBehaviorAnalyzer;
import com.youkeda.exercise.claw.feature.scout.context.UserContextService;
import com.youkeda.exercise.claw.feature.scout.context.UserProfile;
import com.youkeda.exercise.claw.feature.scout.judge.DecisionMaker;
import com.youkeda.exercise.claw.feature.scout.judge.Recommendation;
import com.youkeda.exercise.claw.feature.scout.matcher.CandidateMatcher;
import com.youkeda.exercise.claw.feature.scout.matcher.MatchedCandidate;
import com.youkeda.exercise.claw.feature.scout.notifier.NotificationService;
import com.youkeda.exercise.claw.feature.scout.planner.SearchPlanner;
import com.youkeda.exercise.claw.feature.scout.planner.SearchTask;
import com.youkeda.exercise.claw.feature.scout.processor.InformationItem;
import com.youkeda.exercise.claw.feature.scout.processor.InformationProcessor;
import com.youkeda.exercise.claw.feature.scout.store.InformationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 信息猎手总调度器
 *
 * 拆分为四个独立定时任务：
 * 1. 信息采集（每 4 小时）— 搜索 → 处理 → 存入信息库
 * 2. 推荐决策（每天早 8 点）— 匹配 → 判断 → 推送
 * 3. 画像更新（每天 19:45）— 从对话更新用户画像
 * 4. 过期清理（每天凌晨 3 点）— 清理过期信息
 */
@Component
@ConditionalOnProperty(name = "scout.enabled", havingValue = "true")
public class ScoutOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ScoutOrchestrator.class);

    private final UserContextService contextService;
    private final UserBehaviorAnalyzer behaviorAnalyzer;
    private final SearchPlanner planner;
    private final CollectorRegistry collectorRegistry;
    private final InformationProcessor processor;
    private final InformationStore store;
    private final CandidateMatcher matcher;
    private final DecisionMaker decisionMaker;
    private final NotificationService notifier;
    private final ScoutProperties props;
    private ScoutKnowledgeProvider knowledgeProvider;

    public ScoutOrchestrator(UserContextService contextService,
                              UserBehaviorAnalyzer behaviorAnalyzer,
                              SearchPlanner planner,
                              CollectorRegistry collectorRegistry,
                              InformationProcessor processor,
                              InformationStore store,
                              CandidateMatcher matcher,
                              DecisionMaker decisionMaker,
                              NotificationService notifier,
                              ScoutProperties props) {
        this.contextService = contextService;
        this.behaviorAnalyzer = behaviorAnalyzer;
        this.planner = planner;
        this.collectorRegistry = collectorRegistry;
        this.processor = processor;
        this.store = store;
        this.matcher = matcher;
        this.decisionMaker = decisionMaker;
        this.notifier = notifier;
        this.props = props;
    }

    @Autowired
    void setKnowledgeProvider(ScoutKnowledgeProvider knowledgeProvider) {
        this.knowledgeProvider = knowledgeProvider;
    }

    // ==================== 手动触发：完整流程 ====================


    /**
     * 执行信息猎手完整流程（手动触发时使用）
     */
    public ScoutReport run() {
        return run(ScoutExecutionContext.withoutKnowledge(""));
    }

    public ScoutReport run(String explicitQuery) {
        return run(ScoutExecutionContext.withoutKnowledge(explicitQuery));
    }

    public ScoutReport run(ScoutExecutionContext executionContext) {
        ScoutExecutionContext context = executionContext == null
                ? ScoutExecutionContext.withoutKnowledge("") : executionContext;
        String explicitQuery = context.explicitQuery();
        log.info("========== 信息猎手启动（完整流程）==========");

        try {
            // 1. 构建用户画像
            log.info("[1/7] 构建用户画像...");
            UserProfile profile = contextService.buildProfile();

            // 2. 生成搜索任务
            log.info("[2/7] 生成搜索任务...");
            List<SearchTask> tasks = planner.plan(
                    profile, explicitQuery, context.planningKnowledge());
            log.info("搜索任务 | count={}", tasks.size());

            // 3. 多源采集
            log.info("[3/7] 多源信息采集...");
            List<InformationItem> rawItems = collectorRegistry.collectAll(tasks);
            log.info("采集结果 | count={}", rawItems.size());

            if (rawItems.isEmpty()) {
                log.info("无采集结果，流程结束");
                return new ScoutReport(tasks.size(), 0, 0);
            }

            // 4. 信息处理
            log.info("[4/7] 信息处理...");
            List<InformationItem> processed = processor.process(rawItems);
            log.info("处理结果 | count={}", processed.size());

            // 5. 存入信息库
            log.info("[5/7] 存入信息库...");
            store.batchSave(processed);

            // 6. 语义匹配
            log.info("[6/7] 语义匹配...");
            List<MatchedCandidate> candidates = matcher.match(profile, explicitQuery, processed);
            log.info("匹配结果 | count={}", candidates.size());

            // 7. LLM 价值判断 + 推送
            log.info("[7/7] LLM 价值判断...");
            List<Recommendation> recommendations = decisionMaker.judge(
                    profile, candidates, context.decisionKnowledge());
            log.info("推荐结果 | count={}", recommendations.size());

            // 推送
            notifier.notifyWithSummary(recommendations);

            ScoutReport report = new ScoutReport(tasks.size(), processed.size(), recommendations.size());
            log.info("========== 信息猎手完成 | report={} ==========", report);
            return report;

        } catch (Exception e) {
            log.error("信息猎手执行异常", e);
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("信息猎手执行失败", e);
        }
    }

    // ==================== 定时任务 1：信息采集（每 4 小时） ====================

    /**
     * 定时采集：只做 搜索 → 处理 → 存库，不推送
     */
    @Scheduled(cron = "0 0 */4 * * *")
    public void scheduledCollect() {
        log.info("========== 定时采集启动 ==========");
        try {
            UserProfile profile = contextService.buildProfile();
            ScoutExecutionContext executionContext = scheduledKnowledgeContext();
            List<SearchTask> tasks = planner.plan(
                    profile, "", executionContext.planningKnowledge());
            List<InformationItem> rawItems = collectorRegistry.collectAll(tasks);

            if (rawItems.isEmpty()) {
                log.info("采集无结果");
                return;
            }

            List<InformationItem> processed = processor.process(rawItems);
            store.batchSave(processed);
            log.info("采集完成 | tasks={} | stored={}", tasks.size(), processed.size());
        } catch (Exception e) {
            log.error("定时采集失败", e);
        }
        log.info("========== 定时采集完成 ==========");
    }

    // ==================== 定时任务 2：推荐决策（每天早 8 点） ====================

    /**
     * 定时推荐：每天固定时间，从信息库中匹配 → 判断 → 推送
     */
    @Scheduled(cron = "${scout.cron:0 0 8 * * *}")
    public void scheduledRecommend() {
        log.info("========== 定时推荐启动 ==========");
        try {
            UserProfile profile = contextService.buildProfile();

            // 从信息库中取最近的信息
            List<InformationItem> recentItems = store.getRecent(props.getMaxCandidates() * 3);
            if (recentItems.isEmpty()) {
                log.info("信息库为空，跳过推荐");
                return;
            }

            // 语义匹配
            List<MatchedCandidate> candidates = matcher.match(profile, recentItems);
            if (candidates.isEmpty()) {
                log.info("无匹配候选，跳过推荐");
                return;
            }

            // LLM 判断
            ScoutExecutionContext executionContext = scheduledKnowledgeContext();
            List<Recommendation> recommendations = decisionMaker.judge(
                    profile, candidates, executionContext.decisionKnowledge());

            // 推送
            notifier.notifyWithSummary(recommendations);
            log.info("推荐完成 | candidates={} | recommended={}", candidates.size(), recommendations.size());
        } catch (Exception e) {
            log.error("定时推荐失败", e);
        }
        log.info("========== 定时推荐完成 ==========");
    }

    // ==================== 定时任务 3：画像更新（每天 19:45） ====================

    /**
     * 定时画像更新：分析用户行为 + 重建画像
     */
    @Scheduled(cron = "0 45 19 * * *")
    public void scheduledProfileUpdate() {
        log.info("========== 定时画像更新 ==========");
        try {
            // 1. 从对话历史提取新兴趣，存入长期记忆
            int newInterests = behaviorAnalyzer.analyzeAndStore();
            if (newInterests > 0) {
                log.info("发现新兴趣 | count={}", newInterests);
            }

            // 2. 重建用户画像（基于最新的长期记忆）
            contextService.buildProfile();
            log.info("画像更新完成");
        } catch (Exception e) {
            log.error("画像更新失败", e);
        }
    }

    // ==================== 定时任务 4：过期清理（每天凌晨 3 点） ====================

    private ScoutExecutionContext scheduledKnowledgeContext() {
        return knowledgeProvider == null
                ? ScoutExecutionContext.withoutKnowledge("")
                : knowledgeProvider.forScheduledRun();
    }

    /**
     * 定时清理：删除过期信息
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void scheduledCleanup() {
        long expireBefore = Instant.now()
                .minus(props.getTtlDays(), ChronoUnit.DAYS)
                .toEpochMilli();

        log.info("========== 定时清理启动 | ttlDays={} ==========", props.getTtlDays());
        try {
            store.deleteExpired(expireBefore);
            log.info("过期清理完成");
        } catch (Exception e) {
            log.error("过期清理失败", e);
        }
    }
}
