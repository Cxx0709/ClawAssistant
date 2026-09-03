package com.youkeda.exercise.claw.web;

import com.youkeda.exercise.claw.agent.AgentContext;
import com.youkeda.exercise.claw.agent.AgentStreamObserver;
import com.youkeda.exercise.claw.agent.ReActAgentExecutor;
import com.youkeda.exercise.claw.agent.model.MessageKind;
import com.youkeda.exercise.claw.ai.vision.VisionService;
import com.youkeda.exercise.claw.ai.voice.VoiceService;
import com.youkeda.exercise.claw.artifact.ArtifactKind;
import com.youkeda.exercise.claw.artifact.ArtifactService;
import com.youkeda.exercise.claw.artifact.RequestArtifactCollector;
import com.youkeda.exercise.claw.infrastructure.document.FileParseService;
import org.springframework.stereotype.Service;
import com.youkeda.exercise.claw.web.conversation.ConversationService;
import com.youkeda.exercise.claw.web.conversation.ConversationTitleGenerator;

import java.nio.file.Files;
import java.util.Base64;
import java.util.List;

@Service
public class ChatApplicationService {

    private static final String FALLBACK_REPLY = "抱歉，我现在暂时无法回复，请稍后再试。";

    private final ReActAgentExecutor agentExecutor;
    private final ArtifactService artifactService;
    private final VisionService visionService;
    private final VoiceService voiceService;
    private final FileParseService fileParseService;
    private final ConversationService conversationService;

    public ChatApplicationService(ReActAgentExecutor agentExecutor,
                                  ArtifactService artifactService,
                                  VisionService visionService,
                                  VoiceService voiceService,
                                  FileParseService fileParseService,
                                  ConversationService conversationService) {
        this.agentExecutor = agentExecutor;
        this.artifactService = artifactService;
        this.visionService = visionService;
        this.voiceService = voiceService;
        this.fileParseService = fileParseService;
        this.conversationService = conversationService;
    }

    public ChatResponse execute(String userId, String message, List<String> attachmentIds,
                                String requestId, AgentStreamObserver observer) {
        return execute(userId, null, message, attachmentIds, requestId, observer);
    }

    public ChatResponse execute(String userId, String conversationId, String message,
                                List<String> attachmentIds, String requestId,
                                AgentStreamObserver observer) {
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
