package com.youkeda.exercise.claw.notification;

import com.youkeda.exercise.claw.notification.model.NotificationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 统一通知事件消费者（批次 3 事件总线落地）。
 *
 * <p>所有 {@link NotificationSource} 产出 {@link NotificationEvent} 后，由本组件
 * 将领域事件统一投递到持久化站内通知。事件与展示通道解耦——
 * 未来扩展 email/webpush 等通道只需新增消费者，Source 无需修改。
 *
 * <p>单用户场景无需异步监听器：同步 publish 即可，投递失败记日志不抛出
 * （与旧 {@code NotificationService.deliver} 容错语义一致，避免阻断 Source 主流程）。
 */
@Component
public class NotificationEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventPublisher.class);

    private final NotificationSink notificationSink;

    public NotificationEventPublisher(NotificationSink notificationSink) {
        this.notificationSink = notificationSink;
    }

    /**
     * 发布通知事件，投递到站内通知。
     *
     * <p>content 承载 Source 拼好的完整文案（含 emoji 头）；title/coverUrl/priority/
     * timestamp 随事件携带，供未来扩展展示与排序使用。
     */
    public void publish(NotificationEvent event) {
        if (event == null) {
            log.warn("通知事件为空，跳过");
            return;
        }
        String content = event.getContent();
        if (content == null || content.isBlank()) {
            log.warn("通知内容为空，跳过 | source={}", event.getSource());
            return;
        }
        try {
            int count = notificationSink.publishToAll(event.getSource(), event.getTitle(), content,
                    event.getPriority(), null);
            log.info("通知已写入站内收件箱 | source={} | title={} | recipients={}",
                    event.getSource(), event.getTitle(), count);
        } catch (Exception e) {
            log.error("通知发送异常 | source={} | title={}", event.getSource(), event.getTitle(), e);
        }
    }

    /** 便捷重载：从 Source 侧直接构造并发布（source 用于日志） */
    public void publish(String source, String title, String content, int priority) {
        publish(new NotificationEvent(source, title, content, null, priority,
                System.currentTimeMillis() / 1000));
    }
}
