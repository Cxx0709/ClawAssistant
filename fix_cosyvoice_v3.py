with open(r'C:\Users\han\ClawAssistant\src\main\resources\application.properties', 'r', encoding='utf-8') as f:
    content = f.read()

# 升级模型到 cosyvoice-v3.5-plus，支持零样本声音克隆
content = content.replace('voice.tts-model=cosyvoice-v2', 'voice.tts-model=cosyvoice-v3.5-plus')
# 升级默认音色到 v3 版本（longxiaochun 对应 v3 的音色）
content = content.replace('voice.tts-voice=longxiaochun_v2', 'voice.tts-voice=longxiaochun')

with open(r'C:\Users\han\ClawAssistant\src\main\resources\application.properties', 'w', encoding='utf-8') as f:
    f.write(content)
print('Upgraded TTS model to cosyvoice-v3.5-plus')

# 修改 VoiceClient 的 buildTtsRequestBody，加 prompt_text 参数
with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\ai\voice\VoiceClient.java', 'r', encoding='utf-8') as f:
    content = f.read()

old_build = """    private String buildTtsRequestBody(String text, String promptAudioUrl) throws Exception {
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

new_build = """    private String buildTtsRequestBody(String text, String promptAudioUrl) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.getTtsModel());

        ObjectNode input = root.putObject("input");
        input.put("text", text);
        if (promptAudioUrl != null && !promptAudioUrl.isBlank()) {
            // 零样本声音克隆：传入参考音频 URL + 参考音频文本
            input.put("prompt_audio", promptAudioUrl);
            // prompt_text 是参考音频对应的文本，用于提升克隆准确度
            // 如果不知道参考音频的文本，可以传空或简单描述
            input.put("prompt_text", "");
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
print('Added prompt_text parameter to TTS request body')
