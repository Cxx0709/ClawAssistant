package com.youkeda.exercise.claw.notification;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 邮件通知通道。
 *
 * <p>与站内通知（{@link DatabaseNotificationSink}）并行：所有写入 outbox 的通知，
 * 如果用户绑定了邮箱且开启了邮件通知，同时投递一封邮件。
 *
 * <p>设计原则：
 * <ul>
 *   <li>邮件是旁路通道，发送失败只记日志，绝不阻断站内通知主流程。</li>
 *   <li>JavaMailSender 用 ObjectProvider 注入，依赖未加载时自动降级，不影响应用启动。</li>
 *   <li>未配置 SMTP（spring.mail.username 为空）时自动降级为 no-op。</li>
 *   <li>mail.notification.enabled=false 时全局关闭邮件通道。</li>
 * </ul>
 */
@Service
public class EmailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${mail.notification.enabled:true}")
    private boolean enabled;

    @Value("${mail.notification.from-name:Zhixing}")
    private String fromName;

    @Value("${mail.notification.subject-prefix:[Zhixing Reminder]}")
    private String subjectPrefix;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    public EmailNotificationService(ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.mailSenderProvider = mailSenderProvider;
    }

    @PostConstruct
    void init() {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.warn("JavaMailSender 未就绪（可能 spring-boot-starter-mail 依赖未加载），邮件通道已禁用");
            return;
        }
        if (!enabled) {
            log.info("邮件通知通道已全局关闭 (mail.notification.enabled=false)");
        } else if (fromAddress == null || fromAddress.isBlank()) {
            log.warn("邮件通知通道已启用但未配置 spring.mail.username，运行时将自动跳过邮件发送");
        } else {
            log.info("邮件通知通道已就绪 | from={} | subjectPrefix={}", fromAddress, subjectPrefix);
        }
    }

    /**
     * 发送一封通知邮件。
     *
     * @param toEmail  收件人邮箱地址（为空则直接跳过）
     * @param title    通知标题（用于邮件主题）
     * @param content  通知正文（纯文本，会自动转 HTML 排版）
     * @param source   通知来源标识（如 REMINDER / EXAM / ANIME），用于日志
     * @return true 表示已提交发送（不代表对方一定收到），false 表示被跳过或发送失败
     */
    public boolean sendNotification(String toEmail, String title, String content, String source) {
        // 1. 前置校验：全局开关 / 发件人配置 / 收件人 / JavaMailSender 是否就绪
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.debug("JavaMailSender 未就绪，跳过邮件 | source={}", source);
            return false;
        }
        if (!enabled) {
            log.debug("邮件通道全局关闭，跳过 | source={}", source);
            return false;
        }
        if (fromAddress == null || fromAddress.isBlank()) {
            log.debug("未配置 SMTP 发件人，跳过邮件 | source={}", source);
            return false;
        }
        if (toEmail == null || toEmail.isBlank()) {
            log.debug("收件人邮箱为空，跳过 | source={}", source);
            return false;
        }
        if (content == null || content.isBlank()) {
            log.warn("邮件正文为空，跳过 | source={} | to={}", source, toEmail);
            return false;
        }

        try {
            String subject = buildSubject(title);
            String htmlBody = buildHtmlBody(title, content, source);

            mailSender.send(mimeMessage -> {
                MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
                helper.setFrom(fromAddress, fromName);
                helper.setTo(toEmail.trim());
                mimeMessage.setSubject(subject, "UTF-8");
                helper.setText(htmlBody, true); // true = HTML
            });

            log.info("通知邮件已发送 | source={} | to={} | subject={}", source, toEmail, subject);
            return true;
        } catch (Exception e) {
            // 邮件是旁路通道，失败只记日志，不抛出
            log.error("通知邮件发送失败 | source={} | to={} | title={} | error={}",
                    source, toEmail, title, e.getMessage(), e);
            return false;
        }
    }

    private String buildSubject(String title) {
        String safeTitle = (title == null || title.isBlank()) ? "新通知" : title;
        return subjectPrefix + " " + safeTitle;
    }

    /**
     * 构造简单 HTML 邮件正文。
     * 纯文本内容做基本 HTML 转义，保留换行。
     */
    private String buildHtmlBody(String title, String content, String source) {
        String escapedContent = escapeHtml(content)
                .replace("\n", "<br>")
                .replace("  ", "&nbsp;&nbsp;");
        String escapedTitle = escapeHtml(title == null ? "新通知" : title);
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now());

        return """
                <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 560px; margin: 0 auto; padding: 24px;">
                  <div style="background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%); border-radius: 12px 12px 0 0; padding: 20px 24px; color: white;">
                    <h2 style="margin: 0; font-size: 18px; font-weight: 600;">%s</h2>
                    <p style="margin: 6px 0 0 0; font-size: 12px; opacity: 0.85;">来源：%s · %s</p>
                  </div>
                  <div style="background: #ffffff; border: 1px solid #e5e7eb; border-top: none; border-radius: 0 0 12px 12px; padding: 24px;">
                    <div style="font-size: 15px; line-height: 1.7; color: #1f2937;">%s</div>
                    <hr style="border: none; border-top: 1px solid #e5e7eb; margin: 20px 0;">
                    <p style="font-size: 12px; color: #9ca3af; margin: 0;">此邮件由 知行 自动发送，请勿直接回复。如需关闭邮件提醒，请在应用设置中修改。</p>
                  </div>
                </div>
                """.formatted(escapedTitle, escapeHtml(source), timestamp, escapedContent);
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
