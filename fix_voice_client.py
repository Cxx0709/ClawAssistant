with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\ai\voice\VoiceClient.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 在 tts(String text) 方法后面加一个 tts(String text, String promptAudioUrl) 方法
old_tts_end = """            return new TtsResult(downloadAudio(audioUrl), audioUrl);

        } catch (VoiceClientException e) {
            throw e;
        } catch (Exception e) {
            log.error("TTS 调用失败 | error={}", e.getMessage());
            return null;
        }
    }"""

new_tts_end = """            return new TtsResult(downloadAudio(audioUrl), audioUrl);

        } catch (VoiceClientException e) {
            throw e;
        } catch (Exception e) {
            log.error("TTS 调用失败 | error={}", e.getMessage());
            return null;
        }
    }

    /**
     * 语音合成（TTS）：使用自定义参考音频进行声音克隆（零样本 TTS）
     *
     * @param text           待合成的文字
     * @param promptAudioUrl 参考音频的 DashScope 文件 URL（用于声音克隆）
     * @return TTS 结果（音频字节 + 音频 URL），失败时返回 null
     * @throws VoiceClientException API 返回业务错误码时抛出
     */
    public TtsResult tts(String text, String promptAudioUrl) throws VoiceClientException {
        try {
            if (text == null || text.trim().isEmpty()) {
                log.warn("TTS 输入文本为空");
                return null;
            }
            if (promptAudioUrl == null || promptAudioUrl.isBlank()) {
                // 没有参考音频，回退到默认声音
                return tts(text);
            }

            String requestBody = buildTtsRequestBody(text, promptAudioUrl);
            log.info("调用 CosyVoice TTS（声音克隆）| text={} | promptAudio={}", text, promptAudioUrl);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getTtsBaseUrl()))
                    .timeout(Duration.ofSeconds(TTS_TIMEOUT_SECONDS))
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            String body = response.body();
            JsonNode root = objectMapper.readTree(body);

            // 检查 API 错误码
            JsonNode codeNode = root.get("code");
            if (codeNode != null && !codeNode.isNull()) {
                String msg = root.path("message").asText("TTS 未知错误");
                log.warn("TTS API 错误 | code={} | message={}", codeNode.asText(), msg);
                throw new VoiceClientException(codeNode.asText(), msg);
            }

            // 非流式响应：提取 output.audio.url 再下载
            JsonNode audioUrlNode = root.path("output").path("audio").path("url");
            if (audioUrlNode == null || audioUrlNode.isNull()) {
                log.warn("TTS 响应缺少音频 URL: {}", body);
                return null;
            }

            String audioUrl = audioUrlNode.asText();
            log.info("TTS（声音克隆）合成成功，获取音频 URL | url={}", audioUrl);

            return new TtsResult(downloadAudio(audioUrl), audioUrl);

        } catch (VoiceClientException e) {
            throw e;
        } catch (Exception e) {
            log.error("TTS（声音克隆）调用失败 | error={}", e.getMessage());
            return null;
        }
    }"""

content = content.replace(old_tts_end, new_tts_end)

# 2. 修改 buildTtsRequestBody 方法，加一个支持 promptAudioUrl 的重载
old_build = """    private String buildTtsRequestBody(String text) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.getTtsModel());

        ObjectNode input = root.putObject("input");
        input.put("text", text);
        input.put("voice", properties.getTtsVoice());
        input.put("format", "mp3");
        input.put("sample_rate", 24000);

        return objectMapper.writeValueAsString(root);
    }"""

new_build = """    private String buildTtsRequestBody(String text) throws Exception {
        return buildTtsRequestBody(text, null);
    }

    private String buildTtsRequestBody(String text, String promptAudioUrl) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.getTtsModel());

        ObjectNode input = root.putObject("input");
        input.put("text", text);
        if (promptAudioUrl != null && !promptAudioUrl.isBlank()) {
            // 声音克隆：传入参考音频 URL
            input.put("prompt_audio", promptAudioUrl);
        } else {
            // 默认声音
            input.put("voice", properties.getTtsVoice());
        }
        input.put("format", "mp3");
        input.put("sample_rate", 24000);

        return objectMapper.writeValueAsString(root);
    }"""

content = content.replace(old_build, new_build)

with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\ai\voice\VoiceClient.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('VoiceClient.java fixed - added voice cloning support')
