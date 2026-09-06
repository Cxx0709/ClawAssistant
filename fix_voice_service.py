with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\ai\voice\VoiceService.java', 'r', encoding='utf-8') as f:
    content = f.read()

old_end = '''        return null;
    }

    /**
     * 语音合成结果
     */'''
new_end = '''        return null;
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
     */'''
content = content.replace(old_end, new_end)

with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\ai\voice\VoiceService.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('VoiceService.java fixed - added voice cloning support')
