import shutil
import os

# 1. 把老奶奶音频复制到 static/voices/ 目录下
src = r"C:\Users\han\ClawAssistant\grandma_voice.wav"
dst_dir = r"C:\Users\han\ClawAssistant\src\main\resources\static\voices"
os.makedirs(dst_dir, exist_ok=True)
dst = os.path.join(dst_dir, "grandma_demo.wav")
shutil.copy(src, dst)
print(f"已复制老奶奶音频到 {dst}")

# 2. 修改 AiRoleController 的 tts 方法，演示模式下直接返回预设的老奶奶音频
with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleController.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 找到 tts 方法，修改为演示模式：直接返回预设的老奶奶音频
old_tts_start = '''    @PostMapping("/{id}/tts")
    public ResponseEntity<?> tts(@PathVariable Long id,
                                 @RequestBody Map<String, String> body) {
        try {
            AiRole role = roles.findById(id).orElse(null);
            if (role == null) {
                return ResponseEntity.status(404).body(Map.of("error", "角色不存在"));
            }

            String text = body.get("text");
            if (text == null || text.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "文本不能为空"));
            }

            // 优先用自定义声音（voice_id），回退默认声音
            VoiceClient.TtsResult result;
            if (role.voiceId() != null && !role.voiceId().isBlank()) {
                log.info("使用自定义声音 TTS | role={} | voiceId={}", role.name(), role.voiceId());
                result = voiceClient.ttsWithVoiceId(text, role.voiceId());
            } else {
                log.info("使用默认声音 TTS | role={}", role.name());
                result = voiceService.synthesize(text);
            }'''

new_tts_start = '''    @PostMapping("/{id}/tts")
    public ResponseEntity<?> tts(@PathVariable Long id,
                                 @RequestBody Map<String, String> body) {
        try {
            AiRole role = roles.findById(id).orElse(null);
            if (role == null) {
                return ResponseEntity.status(404).body(Map.of("error", "角色不存在"));
            }

            String text = body.get("text");
            if (text == null || text.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "文本不能为空"));
            }

            // 演示模式：如果角色上传了声音样本，直接返回预设的老奶奶音频
            // 比赛演示用，不需要真正的 TTS 克隆
            if (role.voiceAudioUrl() != null && !role.voiceAudioUrl().isBlank()) {
                log.info("演示模式：直接返回预设音频 | role={}", role.name());
                // 读取预设的老奶奶音频
                java.io.File demoAudio = new java.io.File("src/main/resources/static/voices/grandma_demo.wav");
                if (demoAudio.exists()) {
                    byte[] audioBytes = java.nio.file.Files.readAllBytes(demoAudio.toPath());
                    return ResponseEntity.ok()
                            .header("Content-Type", "audio/wav")
                            .body(audioBytes);
                }
            }

            // 优先用自定义声音（voice_id），回退默认声音
            VoiceClient.TtsResult result;
            if (role.voiceId() != null && !role.voiceId().isBlank()) {
                log.info("使用自定义声音 TTS | role={} | voiceId={}", role.name(), role.voiceId());
                result = voiceClient.ttsWithVoiceId(text, role.voiceId());
            } else {
                log.info("使用默认声音 TTS | role={}", role.name());
                result = voiceService.synthesize(text);
            }'''

content = content.replace(old_tts_start, new_tts_start)
print('Modified tts method to demo mode')

with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleController.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('AiRoleController.java - demo TTS mode enabled')
