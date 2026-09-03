package com.youkeda.exercise.claw.agent;

import com.youkeda.exercise.claw.agent.model.PlanState;
import com.youkeda.exercise.claw.agent.model.MessageKind;
import com.youkeda.exercise.claw.agent.skill.SkillSession;
import com.youkeda.exercise.claw.artifact.ArtifactCollector;

/**
 * Agent 执行上下文
 *
 * 封装一次 Agent 调用的所有输入信息，贯穿整个执行链路。
 * {@link #planState} 为可选字段——非多步骤任务时为 null，0 额外开销。
 */
public class AgentContext {

    /**
     * 用户 ID
     */
    private String userId;

    /**
     * 上下文 Token
     */
    private String contextToken;

    /**
     * 用户消息文本（TEXT 类型时有效）
     */
    private String message;

    /**
     * 消息类型（TEXT / IMAGE）
     */
    private MessageKind messageType = MessageKind.TEXT;

    /** Request-scoped output collector; never shared between concurrent users. */
    private ArtifactCollector artifacts = ArtifactCollector.noop();

    /**
     * 当前会话的计划状态（可选，简单任务时为 null）
     */
    private PlanState planState;

    /**
     * 当前 Skill 会话状态（可选，非 skill 任务时为 null）
     */
    private SkillSession skillSession;

    /**
     * 轮次 ID（ADR Phase 1B）：用户消息已在落库时 beginTurn，roundId 随消息贯通至此；
     * 系统触发（定时任务）为 null，由 executor 自行 beginTurn。
     */
    private String roundId;

    /**
     * 外部指定的活动记录请求 ID（流式场景：Web 端需在请求前订阅 recorder，故由入口预生成）。
     * 未预订阅活动流的入口为 null，由 executor 自行 {@code beginRequest()}。
     */
    private String activityRequestId;

    /**
     * 流式观察者（Web 端逐字推送用）。为 null 时不产生流式副作用。
     */
    private AgentStreamObserver streamObserver;


    public String getUserId() {
        return userId;
    }

    public AgentContext setUserId(String userId) {
        this.userId = userId;
        return this;
    }

    public String getContextToken() {
        return contextToken;
    }

    public AgentContext setContextToken(String contextToken) {
        this.contextToken = contextToken;
        return this;
    }

    public SkillSession getSkillSession() {
        return skillSession;
    }

    public AgentContext setSkillSession(SkillSession skillSession) {
        this.skillSession = skillSession;
        return this;
    }

    public String getMessage() {
        return message;
    }

    public AgentContext setMessage(String message) {
        this.message = message;
        return this;
    }

    public MessageKind getMessageType() {
        return messageType;
    }

    public AgentContext setMessageType(MessageKind messageType) {
        this.messageType = messageType;
        return this;
    }

    public ArtifactCollector getArtifacts() {
        return artifacts;
    }

    public AgentContext setArtifacts(ArtifactCollector artifacts) {
        this.artifacts = artifacts == null ? ArtifactCollector.noop() : artifacts;
        return this;
    }

    public PlanState getPlanState() { return planState; }

    public AgentContext setPlanState(PlanState planState) {
        this.planState = planState;
        return this;
    }

    public String getRoundId() {
        return roundId;
    }

    public AgentContext setRoundId(String roundId) {
        this.roundId = roundId;
        return this;
    }

    public String getActivityRequestId() {
        return activityRequestId;
    }

    public AgentContext setActivityRequestId(String activityRequestId) {
        this.activityRequestId = activityRequestId;
        return this;
    }

    public AgentStreamObserver getStreamObserver() {
        return streamObserver;
    }

    public AgentContext setStreamObserver(AgentStreamObserver streamObserver) {
        this.streamObserver = streamObserver;
        return this;
    }

}
