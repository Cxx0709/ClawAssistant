with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleController.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 修改 TTS 端点，支持 'default' 角色ID
old_tts = """    @PostMapping("/{id}/tts")
    public ResponseEntity<byte[]> tts(Authentication authentication, @PathVariable String id,
                                        @RequestBody Map<String, String> body) {
        return scoped(authentication, () -> {
            AiRole role = roles.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在"));
            String text = body.get("text");
            if (text == null || text.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入要合成的文本");
            }
            // 如果角色有自定义声音，用声音克隆；否则用默认声音
            String promptAudioUrl = role.voiceAudioUrl();
            // 如果是本地路径，转成完整 URL（DashScope 需要可访问的 URL，这里我们先简化处理）
            // 实际上 DashScope 的 prompt_audio 需要是公网可访问的 URL
            // 本地开发时我们先用默认声音，等部署后再用自定义声音
            VoiceService.VoiceSynthesisResult result = voiceService.synthesize(text, null);
            if (result == null || result.getAudioBytes() == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "语音合成失败");
            }
            return ResponseEntity.ok()
                    .header("Content-Type", "audio/mpeg")
                    .header("Content-Disposition", "inline; filename=tts.mp3")
                    .body(result.getAudioBytes());
        });
    }"""

new_tts = """    @PostMapping("/{id}/tts")
    public ResponseEntity<byte[]> tts(Authentication authentication, @PathVariable String id,
                                        @RequestBody Map<String, String> body) {
        return scoped(authentication, () -> {
            String text = body.get("text");
            if (text == null || text.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入要合成的文本");
            }
            // 'default' 或找不到角色时用默认声音
            String promptAudioUrl = null;
            if (!"default".equals(id)) {
                var roleOpt = roles.findById(id);
                if (roleOpt.isPresent()) {
                    // 角色有自定义声音时，先上传到 DashScope 获取公网 URL（本地开发暂用默认声音）
                    // promptAudioUrl = roleOpt.get().voiceAudioUrl();
                    // 本地开发时 DashScope prompt_audio 需要公网 URL，暂用默认声音
                    promptAudioUrl = null;
                }
            }
            VoiceService.VoiceSynthesisResult result = voiceService.synthesize(text, promptAudioUrl);
            if (result == null || result.getAudioBytes() == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "语音合成失败");
            }
            return ResponseEntity.ok()
                    .header("Content-Type", "audio/mpeg")
                    .header("Content-Disposition", "inline; filename=tts.mp3")
                    .body(result.getAudioBytes());
        });
    }"""

content = content.replace(old_tts, new_tts)

with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleController.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('AiRoleController.java fixed - TTS endpoint supports default role')
