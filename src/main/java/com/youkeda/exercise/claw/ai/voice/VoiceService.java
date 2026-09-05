package com.youkeda.exercise.claw.ai.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.ai.voice.VoiceClient;
import com.youkeda.exercise.claw.ai.voice.VoiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 语音服务
 *
 * 封装语音业务的逻辑：
 * - ASR（语音识别）：调用 VoiceClient 将音频转为文字，含重试
 * - TTS（语音合成）：调用 VoiceClient 将文字转为语音，含重试
 */
@Service
public class VoiceService {

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

    private static final int MAX_RETRIES = 3;

    private final VoiceClient voiceClient;
    private final VoiceProperties voiceProperties;
    private final ObjectMapper objectMapper;

    public VoiceService(VoiceClient voiceClient,
                         VoiceProperties voiceProperties,
                         ObjectMapper objectMapper) {
        this.voiceClient = voiceClient;
        this.voiceProperties = voiceProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 语音识别：音频转文字（含重试）
     *
     * @param audioBytes 音频字节数据
     * @param encodeType 音频编码类型
     * @return 识别文本，失败时返回 null
     */
    public String transcribe(byte[] audioBytes, int encodeType) {
        return transcribeWithRetry(() -> voiceClient.asr(audioBytes, encodeType));
    }

    /** Transcribes a browser upload while preserving its codec-bearing file extension. */
    public String transcribe(byte[] audioBytes, String fileName) {
        return transcribeWithRetry(() -> voiceClient.asr(audioBytes, fileName));
    }

    private String transcribeWithRetry(AsrCall call) {
        int attempt = 0;
        while (attempt < MAX_RETRIES) {
            try {
                String text = call.execute();
                if (text != null && !text.trim().isEmpty()) {
                    log.info("ASR 成功 | text={}", text);
                    return text.trim();
                }
                log.warn("ASR 返回空文本");
                return null;
            } catch (VoiceClientException e) {
                attempt++;
                if (attempt >= MAX_RETRIES) {
                    log.error("ASR 失败，已达最大重试次数 {} | errorCode={}",
                            MAX_RETRIES, e.getErrorCode());
                    return null;
                }
                log.warn("ASR 失败 (第{}/{}) | errorCode={}，{}ms 后重试",
                        attempt, MAX_RETRIES, e.getErrorCode(), 2000L * attempt);
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            } catch (Exception e) {
                log.error("ASR 异常", e);
                return null;
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface AsrCall {
        String execute() throws VoiceClientException;
    }

    /**
     * 语音合成：文字转语音（含重试）
     *
     * @param text 待合成的文字
     * @return 语音合成结果（含音频字节和时长），失败时返回 null
     */
    public VoiceSynthesisResult synthesize(String text) {
        int attempt = 0;
        while (attempt < MAX_RETRIES) {
            try {
                VoiceClient.TtsResult ttsResult = voiceClient.tts(text);
                if (ttsResult != null && ttsResult.audioBytes() != null
                        && ttsResult.audioBytes().length > 0) {
                    byte[] audioBytes = ttsResult.audioBytes();
                    int playtimeMs = voiceClient.parsePlaytime(audioBytes, text);
                    int sampleRate = voiceClient.parseSampleRate(audioBytes);
                    String audioUrl = ttsResult.audioUrl();
                    log.info("TTS 成功 | text={} | size={} | playtime={}ms | sampleRate={}Hz | url={}",
                            text, audioBytes.length, playtimeMs, sampleRate, audioUrl);
                    return new VoiceSynthesisResult(audioBytes, playtimeMs, 4, sampleRate, audioUrl);
                }
                log.warn("TTS 返回空音频");
                return null;
            } catch (VoiceClientException e) {
                attempt++;
                if (attempt >= MAX_RETRIES) {
                    log.error("TTS 失败，已达最大重试次数 {} | errorCode={}",
                            MAX_RETRIES, e.getErrorCode());
                    return null;
                }
                log.warn("TTS 失败 (第{}/{}) | errorCode={}，{}ms 后重试",
                        attempt, MAX_RETRIES, e.getErrorCode(), 2000L * attempt);
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            } catch (Exception e) {
                log.error("TTS 异常", e);
                return null;
            }
        }
        return null;
    }

    /**
     * 语音合成：使用自定义参考音频进行声音克隆（含重试）
     *
     * @param text           待合成的文字
     * @param promptAudioUrl 参考音频的 DashScope 文件 URL（用于声音克隆），为 null 时用默认声音
     * @return 语音合成结果，失败时返回 null
     */
    public VoiceSynthesisResult synthesize(String text, String promptAudioUrl) {
        if (promptAudioUrl == null || promptAudioUrl.isBlank()) {
            return synthesize(text);
        }
        int attempt = 0;
        while (attempt < MAX_RETRIES) {
            try {
                VoiceClient.TtsResult ttsResult = voiceClient.tts(text, promptAudioUrl);
                if (ttsResult != null && ttsResult.audioBytes() != null
                        && ttsResult.audioBytes().length > 0) {
                    byte[] audioBytes = ttsResult.audioBytes();
                    int playtimeMs = voiceClient.parsePlaytime(audioBytes, text);
                    int sampleRate = voiceClient.parseSampleRate(audioBytes);
                    String audioUrl = ttsResult.audioUrl();
                    log.info("TTS voice-clone success | text={} | size={} | playtime={}ms | sampleRate={}Hz | url={}",
                            text, audioBytes.length, playtimeMs, sampleRate, audioUrl);
                    return new VoiceSynthesisResult(audioBytes, playtimeMs, 4, sampleRate, audioUrl);
                }
                log.warn("TTS voice-clone returned empty audio");
                return null;
            } catch (VoiceClientException e) {
                attempt++;
                if (attempt >= MAX_RETRIES) {
                    log.error("TTS voice-clone failed, max retries {} | errorCode={}",
                            MAX_RETRIES, e.getErrorCode());
                    return null;
                }
                log.warn("TTS voice-clone failed (attempt {}/{}) | errorCode={}, retry in {}ms",
                        attempt, MAX_RETRIES, e.getErrorCode(), 2000L * attempt);
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            } catch (Exception e) {
                log.error("TTS voice-clone exception", e);
                return null;
            }
        }
        return null;
    }

    /**
     * 语音合成结果
     */
    public static class VoiceSynthesisResult {
        /** 合成音频字节数据 */
        private byte[] audioBytes;
        /** 音频时长（毫秒） */
        private int playtimeMs;
        /** 音频编码类型 */
        private int encodeType;
        /** 音频采样率（Hz） */
        private int sampleRate;
        /** 音频 URL（OSS 下载地址，可存上下文复用） */
        private String audioUrl;

        public VoiceSynthesisResult(byte[] audioBytes, int playtimeMs, int encodeType, int sampleRate, String audioUrl) {
            this.audioBytes = audioBytes;
            this.playtimeMs = playtimeMs;
            this.encodeType = encodeType;
            this.sampleRate = sampleRate;
            this.audioUrl = audioUrl;
        }

        public byte[] getAudioBytes() {
            return audioBytes;
        }

        public void setAudioBytes(byte[] audioBytes) {
            this.audioBytes = audioBytes;
        }

        public int getPlaytimeMs() {
            return playtimeMs;
        }

        public void setPlaytimeMs(int playtimeMs) {
            this.playtimeMs = playtimeMs;
        }

        public int getEncodeType() {
            return encodeType;
        }

        public void setEncodeType(int encodeType) {
            this.encodeType = encodeType;
        }

        public int getSampleRate() {
            return sampleRate;
        }

        public void setSampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
        }

        public String getAudioUrl() {
            return audioUrl;
        }

        public void setAudioUrl(String audioUrl) {
            this.audioUrl = audioUrl;
        }
    }
}
