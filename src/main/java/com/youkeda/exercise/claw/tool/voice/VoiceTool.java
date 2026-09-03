package com.youkeda.exercise.claw.tool.voice;
import com.youkeda.exercise.claw.agent.runtime.AbstractTool;
import com.youkeda.exercise.claw.agent.runtime.ToolExecutionContext;
import com.youkeda.exercise.claw.agent.runtime.ToolRegistry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.ai.voice.VoiceService;
import com.youkeda.exercise.claw.ai.voice.VoiceService.VoiceSynthesisResult;
import com.youkeda.exercise.claw.artifact.ArtifactKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 语音函数
 *
 * <p>职责：
 * <ul>
 *   <li>ASR 语音识别（提取语音消息文本）</li>
 *   <li>TTS 语音合成（将文本合成为音频文件）</li>
 *   <li>作为 {@link Tool} 提供 {@code text_to_speech} 工具供 LLM 调用</li>
 * </ul>
 *
 * <p>注意：{@link com.youkeda.exercise.claw.agent.runtime.Tool#execute(String)} 只能返回文本，
 * 但 TTS 产生的音频数据通过 {@link #consumePendingAudio()} 传递回调用方
 * （{@code ChatHandler}），确保语音文件能被正确发送。</p>
 */
@Component
public class VoiceTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(VoiceTool.class);

    private final VoiceService voiceService;

    public VoiceTool(VoiceService voiceService,
                      ToolRegistry functionRegistry,
                      ObjectMapper objectMapper) {
        super(functionRegistry, objectMapper);
        this.voiceService = voiceService;
    }

    // ==================== Tool（text_to_speech） ====================

    @Override
    public String getName() {
        return "text_to_speech";
    }

    @Override
    public String getDescription() {
        return "将文本合成为语音音频文件。当用户说「用语音回复」「读给我听」「说给我听」时调用此工具，也适合语音输入场景下对回复内容进行语音播报";
    }

    @Override
    public JsonNode getParameters() {
        return schema()
                .string("text", "需要合成语音的文本内容", true)
                .build();
    }

    @Override
    public String execute(String argumentsJson, ToolExecutionContext context) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            JsonNode textNode = args.get("text");
            if (textNode == null) {
                return "{\"error\": \"缺少必填参数: text\"}";
            }

            String text = textNode.asText();
            log.info("text_to_speech 执行 | text={}", text);

            VoiceSynthesisResult result = voiceService.synthesize(text);
            if (result == null) {
                return "{\"error\": \"语音合成失败\"}";
            }

            log.info("语音合成成功 | size={}bytes | playtime={}ms",
                    result.getAudioBytes().length, result.getPlaytimeMs());

            context.artifacts().emit(ArtifactKind.AUDIO, result.getAudioBytes(), "audio/mpeg",
                    "AI语音回复.mp3", text);

            return "{\"success\": true, \"playtimeMs\": " + result.getPlaytimeMs()
                    + ", \"size\": " + result.getAudioBytes().length + "}";

        } catch (Exception e) {
            log.error("text_to_speech 执行失败 | args={} | error={}", argumentsJson, e.getMessage());
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

}
