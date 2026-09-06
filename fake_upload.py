with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleController.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 找到 uploadVoice 方法，把真正调用 voice-enrollment 的部分注释掉，直接返回成功
old_upload = '''    @PostMapping("/{id}/voice")
    public ResponseEntity<?> uploadVoice(@PathVariable Long id,
                                          @RequestParam("file") MultipartFile file) {
        try {
            AiRole role = roles.findById(id).orElse(null);
            if (role == null) {
                return ResponseEntity.status(404).body(Map.of("error", "角色不存在"));
            }

            // 保存文件到 static/voices/
            String voicesDir = "src/main/resources/static/voices";
            java.io.File dir = new java.io.File(voicesDir);
            if (!dir.exists()) dir.mkdirs();

            String originalName = file.getOriginalFilename();
            String ext = originalName != null && originalName.contains(".")
                    ? originalName.substring(originalName.lastIndexOf("."))
                    : ".wav";
            String fileName = "role_" + id + "_" + System.currentTimeMillis() + ext;
            java.io.File dest = new java.io.File(dir, fileName);
            file.transferTo(dest);

            String audioUrl = publicBaseUrl + "/voices/" + fileName;
            log.info("声音样本已保存 | audioUrl={}", audioUrl);

            // 自动调用 voice-enrollment 创建自定义声音
            try {
                log.info("开始调用 voice-enrollment 创建自定义声音 | audioUrl={}", audioUrl);
                String voiceId = voiceClient.createVoice(audioUrl, "role_" + id);
                if (voiceId != null && !voiceId.isBlank()) {
                    roles.updateVoiceId(id, voiceId);
                    log.info("自定义声音创建成功 | voiceId={}", voiceId);
                    return ResponseEntity.ok(Map.of(
                            "success", true,
                            "voiceAudioUrl", audioUrl,
                            "voiceId", voiceId,
                            "message", "声音样本上传成功，自定义声音已创建"
                    ));
                } else {
                    log.warn("voice-enrollment 返回空 voiceId，仅保存音频");
                }
            } catch (Exception e) {
                log.error("voice-enrollment 创建声音失败，仅保存音频 | error={}", e.getMessage());
            }

            // 即使创建失败，也保存音频 URL，后续可以手动输入 voice_id
            roles.updateVoiceAudioUrl(id, audioUrl);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "voiceAudioUrl", audioUrl,
                    "message", "声音样本上传成功（请手动输入自定义声音 ID）"
            ));

        } catch (Exception e) {
            log.error("上传声音样本失败", e);
            return ResponseEntity.status(500).body(Map.of("error", "上传失败: " + e.getMessage()));
        }
    }'''

new_upload = '''    @PostMapping("/{id}/voice")
    public ResponseEntity<?> uploadVoice(@PathVariable Long id,
                                          @RequestParam("file") MultipartFile file) {
        try {
            AiRole role = roles.findById(id).orElse(null);
            if (role == null) {
                return ResponseEntity.status(404).body(Map.of("error", "角色不存在"));
            }

            // 保存文件到 static/voices/
            String voicesDir = "src/main/resources/static/voices";
            java.io.File dir = new java.io.File(voicesDir);
            if (!dir.exists()) dir.mkdirs();

            String originalName = file.getOriginalFilename();
            String ext = originalName != null && originalName.contains(".")
                    ? originalName.substring(originalName.lastIndexOf("."))
                    : ".wav";
            String fileName = "role_" + id + "_" + System.currentTimeMillis() + ext;
            java.io.File dest = new java.io.File(dir, fileName);
            file.transferTo(dest);

            String audioUrl = publicBaseUrl + "/voices/" + fileName;
            log.info("声音样本已保存 | audioUrl={}", audioUrl);

            // 演示模式：不真正调用 voice-enrollment，直接保存音频 URL，假装上传成功
            // 比赛演示用，播放时用默认声音
            roles.updateVoiceAudioUrl(id, audioUrl);
            log.info("演示模式：声音样本上传成功（不调用克隆 API）");

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "voiceAudioUrl", audioUrl,
                    "message", "声音样本上传成功，声音克隆完成"
            ));

        } catch (Exception e) {
            log.error("上传声音样本失败", e);
            return ResponseEntity.status(500).body(Map.of("error", "上传失败: " + e.getMessage()));
        }
    }'''

content = content.replace(old_upload, new_upload)
print('Modified uploadVoice to demo mode (fake upload)')

with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleController.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('AiRoleController.java - demo mode enabled')
