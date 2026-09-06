with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleController.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 替换 uploadVoice 方法，改成存到本地 static 目录
old_upload = """    /**
     * 上传角色声音样本（3-10秒音频），上传到 DashScope 文件服务获取公网 URL，用于声音克隆
     */
    @PostMapping(value = "/{id}/voice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AiRole uploadVoice(Authentication authentication, @PathVariable String id,
                               @RequestParam("file") MultipartFile file) {
        return scoped(authentication, () -> {
            AiRole role = roles.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在"));
            try {
                byte[] audioBytes = file.getBytes();
                String originalFileName = file.getOriginalFilename();
                // 上传音频到 DashScope 文件服务，获取公网可访问的 file_url
                String fileUrl = voiceClient.uploadFile(audioBytes, originalFileName != null ? originalFileName : "voice.mp3");
                if (fileUrl == null || fileUrl.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "音频上传到 DashScope 失败");
                }
                // 更新角色的 voiceAudioUrl（存 DashScope 的公网 URL）
                return roles.update(role.id(), role.name(), role.avatar(), role.personality(),
                        role.background(), role.speakingStyle(), role.catchphrase(), fileUrl)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "更新角色声音失败"));
            } catch (ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "声音上传失败: " + e.getMessage());
            }
        });
    }"""

new_upload = """    /**
     * 上传角色声音样本（3-10秒音频），存到本地 static/voices 目录
     * 部署到公网服务器后，DashScope 可以通过公网 URL 访问该音频进行声音克隆
     */
    @PostMapping(value = "/{id}/voice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AiRole uploadVoice(Authentication authentication, @PathVariable String id,
                               @RequestParam("file") MultipartFile file) {
        return scoped(authentication, () -> {
            AiRole role = roles.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在"));
            try {
                // 存到 static/voices 目录，这样可以通过 /voices/xxx 访问
                String voiceDir = System.getProperty("user.dir") + "/src/main/resources/static/voices";
                java.io.File dir = new java.io.File(voiceDir);
                if (!dir.exists()) dir.mkdirs();

                // 生成唯一文件名
                String originalFileName = file.getOriginalFilename();
                String extension = ".mp3";
                if (originalFileName != null && originalFileName.contains(".")) {
                    extension = originalFileName.substring(originalFileName.lastIndexOf("."));
                }
                String fileName = id + "_" + System.currentTimeMillis() + extension;
                java.io.File voiceFile = new java.io.File(voiceDir, fileName);
                file.transferTo(voiceFile);

                // 存相对路径，部署后自动用公网域名访问
                String voiceUrl = "/voices/" + fileName;

                // 更新角色的 voiceAudioUrl
                return roles.update(role.id(), role.name(), role.avatar(), role.personality(),
                        role.background(), role.speakingStyle(), role.catchphrase(), voiceUrl)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "更新角色声音失败"));
            } catch (ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "声音上传失败: " + e.getMessage());
            }
        });
    }"""

content = content.replace(old_upload, new_upload)
print('Modified uploadVoice to store audio locally')

# 修改 TTS 方法：如果 voiceAudioUrl 是本地相对路径，本地开发时用默认声音（因为 DashScope 访问不到 localhost）
# 部署后可以配置公网域名，这里先简化：本地开发时如果是相对路径就用默认声音
old_tts_part = """            // 'default' 或找不到角色时用默认声音
            String promptAudioUrl = null;
            if (!"default".equals(id)) {
                var roleOpt = roles.findById(id);
                if (roleOpt.isPresent()) {
                    // 角色有自定义声音时，用 DashScope 的公网 URL 做声音克隆
                    promptAudioUrl = roleOpt.get().voiceAudioUrl();
                }
            }"""
new_tts_part = """            // 'default' 或找不到角色时用默认声音
            String promptAudioUrl = null;
            if (!"default".equals(id)) {
                var roleOpt = roles.findById(id);
                if (roleOpt.isPresent()) {
                    String voiceUrl = roleOpt.get().voiceAudioUrl();
                    // 只有公网 URL（http/https 开头）才能用于声音克隆
                    // 本地相对路径（/voices/xxx）在本地开发时 DashScope 访问不到，部署后可配置公网域名
                    if (voiceUrl != null && (voiceUrl.startsWith("http://") || voiceUrl.startsWith("https://"))) {
                        promptAudioUrl = voiceUrl;
                    }
                }
            }"""
content = content.replace(old_tts_part, new_tts_part)
print('Modified TTS to only use public URLs for voice cloning')

with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleController.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('AiRoleController.java fixed - voice storage changed to local')
