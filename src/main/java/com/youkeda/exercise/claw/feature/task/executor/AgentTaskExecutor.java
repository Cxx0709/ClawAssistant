package com.youkeda.exercise.claw.feature.task.executor;

import com.youkeda.exercise.claw.agent.AgentContext;
import com.youkeda.exercise.claw.agent.ReActAgentExecutor;
import com.youkeda.exercise.claw.feature.task.model.ScheduledTask;
import com.youkeda.exercise.claw.agent.model.MessageKind;
import com.youkeda.exercise.claw.notification.NotificationSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent 任务执行器
 *
 * <p>负责执行 {@link ScheduledTask#TASK_TYPE_AGENT} 类型的定时任务。
 * 接收定时任务后，构造 {@link AgentContext} 并委托 {@link ReActAgentExecutor} 执行，
 * 最后将执行结果写入用户的站内通知。
 *
 * <p>设计原则：
 * <ul>
 *   <li>不复制 ReActAgentExecutor 逻辑</li>
 *   <li>不绕过 Function Calling 架构</li>
 *   <li>Agent 内部自动获取长期记忆（由 ReActAgentExecutor 完成）</li>
 * </ul>
 *
 * <p>执行流程：
 * <pre>
 *   ScheduledTask → AgentTaskExecutor
 *       → 创建 AgentContext（userId + content + TEXT）
 *       → ReActAgentExecutor.execute(context)
 *       → 结果 → NotificationSink.publish()
 * </pre>
 */
@Component
public class AgentTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskExecutor.class);

    private final ReActAgentExecutor agentExecutor;
    private final NotificationSink notificationSink;

    public AgentTaskExecutor(ReActAgentExecutor agentExecutor,
                             NotificationSink notificationSink) {
        this.agentExecutor = agentExecutor;
        this.notificationSink = notificationSink;
    }

    /**
     * 执行 Agent 定时任务
     *
     * <p>将任务 content 作为用户消息，构造上下文后调用 ReActAgentExecutor，
     * 获取回复后将结果写入站内通知。
     *
     * @param task Agent 定时任务（taskType=AGENT）
     * @throws Exception 执行过程中可能抛出的异常（由调用方处理）
     */
    public void execute(ScheduledTask task) throws Exception {
        String userId = task.getUserId();
        String content = task.getContent();

        log.info("AgentTaskExecutor 开始执行 | id={} | userId={} | content={}",
                task.getId(), userId, content);

        // 1. 创建 AgentContext
        AgentContext context = new AgentContext()
                .setUserId(userId)
                .setMessage(content)
                .setMessageType(MessageKind.TEXT);

        // 2. 调用 ReActAgentExecutor
        String result = agentExecutor.execute(context);

        // 3. 持久化为站内通知；浏览器离线时也不会丢失
        String wxMessage = "🤖 Agent 任务执行结果：\n\n"
                + "📋 任务：" + content + "\n\n"
                + "📝 结果：\n" + result;

        notificationSink.publish(userId, "AGENT_TASK", "后台任务已完成", wxMessage, 3, null);

        log.info("AgentTaskExecutor 执行完成 | id={} | userId={} | resultLength={}",
                task.getId(), userId, result != null ? result.length() : 0);
    }
}
