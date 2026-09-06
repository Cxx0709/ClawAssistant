with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\ai\voice\VoiceClient.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 修改 ttsWithVoiceId 方法，用 cosyvoice-v3.5-plus 模型（因为 voice-enrollment 创建的声音 target_model 是 v3.5-plus）
old_method = '''    public TtsResult ttsWithVoiceId(String text, String voiceId) throws VoiceClientException {
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
            log.info("调用 CosyVoice TTS（自定义声音）| text={} | voiceId={}", text, voiceId);'''
new_method = '''    public TtsResult ttsWithVoiceId(String text, String voiceId) throws VoiceClientException {
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
            log.info("调用 CosyVoice TTS（自定义声音，v3.5-plus）| text={} | voiceId={}", text, voiceId);'''
content = content.replace(old_method, new_method)
print('Modified ttsWithVoiceId log')

# 修改 buildTtsRequestBodyWithVoiceId 方法，model 硬编码为 cosyvoice-v3.5-plus
old_build = '''    private String buildTtsRequestBodyWithVoiceId(String text, String voiceId) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.getTtsModel());

        ObjectNode input = root.putObject("input");
        input.put("text", text);
        input.put("voice", voiceId);
        input.put("format", "mp3");
        input.put("sample_rate", 24000);

        return objectMapper.writeValueAsString(root);
    }'''
new_build = '''    private String buildTtsRequestBodyWithVoiceId(String text, String voiceId) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        // 自定义声音必须用 cosyvoice-v3.5-plus 模型（voice-enrollment 创建时 target_model 就是 v3.5-plus）
        root.put("model", "cosyvoice-v3.5-plus");

        ObjectNode input = root.putObject("input");
        input.put("text", text);
        input.put("voice", voiceId);
        input.put("format", "mp3");
        input.put("sample_rate", 24000);

        return objectMapper.writeValueAsString(root);
    }'''
content = content.replace(old_build, new_build)
print('Modified buildTtsRequestBodyWithVoiceId to use cosyvoice-v3.5-plus')

with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\ai\voice\VoiceClient.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('VoiceClient.java fixed - custom voice uses cosyvoice-v3.5-plus')
