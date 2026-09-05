package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.agent.AgentContext;
import com.youkeda.exercise.claw.agent.AgentStreamObserver;
import com.youkeda.exercise.claw.agent.ReActAgentExecutor;
import com.youkeda.exercise.claw.agent.memory.longterm.LongTermMemoryService;
import com.youkeda.exercise.claw.agent.memory.longterm.MemoryCategory;
import com.youkeda.exercise.claw.agent.model.MessageKind;
import com.youkeda.exercise.claw.ai.vision.VisionService;
import com.youkeda.exercise.claw.ai.voice.VoiceService;
import com.youkeda.exercise.claw.artifact.ArtifactKind;
import com.youkeda.exercise.claw.artifact.ArtifactService;
import com.youkeda.exercise.claw.artifact.RequestArtifactCollector;
import com.youkeda.exercise.claw.infrastructure.document.FileParseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.youkeda.exercise.claw.web.conversation.ConversationService;
import com.youkeda.exercise.claw.web.conversation.ConversationTitleGenerator;

import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

@Service
public class ChatApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ChatApplicationService.class);
    private static final String FALLBACK_REPLY = "抱歉，我现在暂时无法回复，请稍后再试。";

    /** 匹配"记住XX"、"帮我记一下XX"、"记一下XX"等开头的记忆保存请求 */
    private static final Pattern MEMORY_SAVE_PATTERN = Pattern.compile(
            "^(?:帮我)?(?:记住|记一下|记下来|帮我记|帮我记一下)\\s*[：:]?\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);

    private final ReActAgentExecutor agentExecutor;
    private final ArtifactService artifactService;
    private final VisionService visionService;
    private final VoiceService voiceService;
    private final FileParseService fileParseService;
    private final ConversationService conversationService;
    private final LongTermMemoryService memoryService;
    private final ExecutorService memoryAutoSaveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "memory-auto-save");
        t.setDaemon(true);
        return t;
    });

    public ChatApplicationService(ReActAgentExecutor agentExecutor,
                                  ArtifactService artifactService,
                                  VisionService visionService,
                                  VoiceService voiceService,
                                  FileParseService fileParseService,
                                  ConversationService conversationService,
                                  LongTermMemoryService memoryService) {
        this.agentExecutor = agentExecutor;
        this.artifactService = artifactService;
        this.visionService = visionService;
        this.voiceService = voiceService;
        this.fileParseService = fileParseService;
        this.conversationService = conversationService;
        this.memoryService = memoryService;
    }

    public ChatResponse execute(String userId, String message, List<String> attachmentIds,
                                String requestId, AgentStreamObserver observer) {
        return execute(userId, null, message, attachmentIds, requestId, observer);
    }

    public ChatResponse execute(String userId, String conversationId, String message,
                                List<String> attachmentIds, String requestId,
                                AgentStreamObserver observer) {
        // 自动保存记忆：用户说"记住XX"时，不管 LLM 有没有调用工具，都自动保存
        autoSaveMemoryIfRequested(userId, message);

        String enrichedMessage = enrichWithAttachments(userId, message, attachmentIds);
        if (conversationId != null) {
            conversationService.requireOwned(userId, conversationId);
            conversationService.touchAfterMessage(userId, conversationId,
                    titleSource(userId, message, attachmentIds));
        }
        RequestArtifactCollector artifacts = new RequestArtifactCollector(artifactService, userId);
        AgentContext context = new AgentContext()
                .setUserId(userId)
                .setConversationId(conversationId)
                .setMessage(enrichedMessage)
                .setMessageType(MessageKind.TEXT)
                .setActivityRequestId(requestId)
                .setStreamObserver(observer)
                .setArtifacts(artifacts);
        String reply = agentExecutor.execute(context);
        boolean silent = ReActAgentExecutor.SILENT_REPLY.equals(reply);
        String text = silent ? "" : (reply == null || reply.isBlank() ? FALLBACK_REPLY : reply);
        return new ChatResponse(text, artifacts.artifacts(), silent);
    }

    /**
     * 检测用户消息是否为"记住XX"形式，如果是则异步自动保存记忆。
     * 不依赖 LLM 是否调用 memory_manage 工具，确保记忆一定能保存。
     */
    private void autoSaveMemoryIfRequested(String userId, String message) {
        if (message == null || message.isBlank()) return;
        String trimmed = message.trim();
        var matcher = MEMORY_SAVE_PATTERN.matcher(trimmed);
        if (!matcher.matches()) return;

        String content = matcher.group(1).trim();
        if (content.isBlank() || content.length() > 200) return;

        // 异步保存，不阻塞用户响应
        memoryAutoSaveExecutor.submit(() -> {
            try {
                boolean saved = memoryService.saveManual(MemoryCategory.PREFERENCE, content);
                log.info("自动保存记忆 | userId={} | content={} | saved={}", userId, content, saved);
            } catch (Exception e) {
                log.warn("自动保存记忆失败 | userId={} | content={} | error={}", userId, content, e.getMessage());
            }
        });
    }

    private String enrichWithAttachments(String userId, String message, List<String> attachmentIds) {
        String base = message == null ? "" : message.trim();
        if (attachmentIds == null || attachmentIds.isEmpty()) return base;
        StringBuilder context = new StringBuilder(base);
        for (String id : attachmentIds.stream().distinct().limit(8).toList()) {
            ArtifactService.StoredArtifact stored = artifactService.load(userId, id)
                    .orElseThrow(() -> new IllegalArgumentException("附件不存在或无权访问: " + id));
            try {
                byte[] bytes = Files.readAllBytes(stored.path());
                String extracted = switch (stored.metadata().kind()) {
                    case IMAGE -> analyzeImage(bytes, stored.metadata().mimeType(), base);
                    case AUDIO -> voiceService.transcribe(bytes, stored.metadata().fileName());
                    case FILE -> {
                        FileParseService.FileParseResult parsed =
                                fileParseService.parse(bytes, stored.metadata().fileName());
                        yield parsed == null ? null : parsed.text();
                    }
                    case BOARD -> null;
                };
                context.append("\n\n[附件：").append(stored.metadata().fileName()).append("]\n")
                        .append(extracted == null || extracted.isBlank() ? "无法解析附件内容" : extracted);
            } catch (Exception e) {
                throw new IllegalArgumentException("附件处理失败: " + stored.metadata().fileName(), e);
            }
        }
        return context.toString();
    }

    private String analyzeImage(byte[] bytes, String mimeType, String question) {
        String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        return visionService.analyze(dataUrl, question.isBlank() ? "请描述图片内容" : question);
    }

    private String titleSource(String userId, String message, List<String> attachmentIds) {
        if (message != null && !message.isBlank()) return message;
        if (attachmentIds == null || attachmentIds.isEmpty()) return "附件对话";
        return artifactService.load(userId, attachmentIds.get(0))
                .map(stored -> ConversationTitleGenerator.fromAttachmentFileName(
                        stored.metadata().fileName()))
                .orElse("附件对话");
    }
}
