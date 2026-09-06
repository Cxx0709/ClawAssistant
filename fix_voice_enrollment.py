with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\ai\voice\VoiceClient.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 加 voice-enrollment API 端点常量
old_constants = '''    /** DashScope 异步任务查询 API */
    private static final String TASK_QUERY_URL = "https://dashscope.aliyuncs.com/api/v1/tasks/";'''
new_constants = '''    /** DashScope 异步任务查询 API */
    private static final String TASK_QUERY_URL = "https://dashscope.aliyuncs.com/api/v1/tasks/";

    /** DashScope 声音复刻（voice-enrollment）API */
    private static final String VOICE_ENROLLMENT_URL = "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/customization";'''
content = content.replace(old_constants, new_constants)
print('Added voice enrollment URL constant')

# 2. 在 tts(String text, String promptAudioUrl) 方法后面加新方法
# 找到 ttsWithVoiceId 方法的插入位置：在 downloadAudio 方法之前
old_download = '''    /**
     * 从音频 URL 下载字节数据
     */
    private byte[] downloadAudio(String audioUrl) throws Exception {'''
new_methods = '''    /**
     * 创建自定义声音（声音复刻）
     *
     * 调用 voice-enrollment API，上传参考音频的公网 URL，创建自定义声音。
     * 这是异步任务，创建成功后返回 voice_id，可用于后续 TTS。
     *
     * @param audioUrl 参考音频的公网可访问 URL
     * @param prefix   声音名称前缀（仅数字/字母，<=10 字符）
     * @return 创建的 voice_id，失败时返回 null
     * @throws VoiceClientException API 返回业务错误码时抛出
     */
    public String createVoice(String audioUrl, String prefix) throws VoiceClientException {
        try {
            if (audioUrl == null || audioUrl.isBlank()) {
                log.warn("创建声音失败：音频 URL 为空");
                return null;
            }

            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", "voice-enrollment");

            ObjectNode input = root.putObject("input");
            input.put("action", "create_voice");
            input.put("target_model", "cosyvoice-v3.5-plus");
            input.put("prefix", prefix != null && !prefix.isBlank() ? prefix : "clawrole");
            input.put("url", audioUrl);

            String requestBody = objectMapper.writeValueAsString(root);
            log.info("调用 voice-enrollment 创建自定义声音 | audioUrl={} | prefix={}", audioUrl, prefix);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(VOICE_ENROLLMENT_URL))
                    .timeout(Duration.ofSeconds(TTS_TIMEOUT_SECONDS))
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            String body = response.body();
            JsonNode respRoot = objectMapper.readTree(body);

            // 检查 API 错误码
            JsonNode codeNode = respRoot.get("code");
            if (codeNode != null && !codeNode.isNull()) {
                String msg = respRoot.path("message").asText("创建声音未知错误");
                log.warn("voice-enrollment API 错误 | code={} | message={}", codeNode.asText(), msg);
                throw new VoiceClientException(codeNode.asText(), msg);
            }

            // 提取 voice_id
            JsonNode voiceIdNode = respRoot.path("output").path("voice_id");
            if (voiceIdNode == null || voiceIdNode.isNull()) {
                // 可能返回的是 task_id，异步任务
                JsonNode taskIdNode = respRoot.path("output").path("task_id");
                log.warn("voice-enrollment 响应缺少 voice_id | body={} | taskId={}",
                        body, taskIdNode != null ? taskIdNode.asText() : "null");
                return null;
            }

            String voiceId = voiceIdNode.asText();
            log.info("自定义声音创建成功 | voiceId={}", voiceId);
            return voiceId;

        } catch (VoiceClientException e) {
            throw e;
        } catch (Exception e) {
            log.error("创建自定义声音失败 | error={}", e.getMessage());
            return null;
        }
    }

    /**
     * 使用自定义声音 ID 进行 TTS
     *
     * @param text    待合成的文字
     * @param voiceId 自定义声音 ID（由 createVoice 创建）
     * @return TTS 结果（音频字节 + 音频 URL），失败时返回 null
     * @throws VoiceClientException API 返回业务错误码时抛出
     */
    public TtsResult ttsWithVoiceId(String text, String voiceId) throws VoiceClientException {
        try {
            if (text == null || text.trim().isEmpty()) {
                log.warn("TTS 输入文本为空");
                return null;
            }
            if (voiceId == null || voiceId.isBlank()) {
                // 没有 voice_id，回退到默认声音
                return tts(text);
            }

            String requestBody = buildTtsRequestBodyWithVoiceId(text, voiceId);
            log.info("调用 CosyVoice TTS（自定义声音）| text={} | voiceId={}", text, voiceId);

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
                log.warn("TTS（自定义声音）API 错误 | code={} | message={}", codeNode.asText(), msg);
                throw new VoiceClientException(codeNode.asText(), msg);
            }

            // 非流式响应：提取 output.audio.url 再下载
            JsonNode audioUrlNode = root.path("output").path("audio").path("url");
            if (audioUrlNode == null || audioUrlNode.isNull()) {
                log.warn("TTS（自定义声音）响应缺少音频 URL: {}", body);
                return null;
            }

            String audioUrl = audioUrlNode.asText();
            log.info("TTS（自定义声音）合成成功，获取音频 URL | url={}", audioUrl);

            return new TtsResult(downloadAudio(audioUrl), audioUrl);

        } catch (VoiceClientException e) {
            throw e;
        } catch (Exception e) {
            log.error("TTS（自定义声音）调用失败 | error={}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建使用自定义 voice_id 的 TTS 请求体
     */
    private String buildTtsRequestBodyWithVoiceId(String text, String voiceId) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.getTtsModel());

        ObjectNode input = root.putObject("input");
        input.put("text", text);
        input.put("voice", voiceId);
        input.put("format", "mp3");
        input.put("sample_rate", 24000);

        return objectMapper.writeValueAsString(root);
    }

    /**
     * 从音频 URL 下载字节数据
     */
    private byte[] downloadAudio(String audioUrl) throws Exception {'''
content = content.replace(old_download, new_methods)
print('Added createVoice and ttsWithVoiceId methods')

with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\ai\voice\VoiceClient.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('VoiceClient.java fixed - voice enrollment support added')
