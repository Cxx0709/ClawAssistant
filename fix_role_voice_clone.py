with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleController.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 加 VoiceClient import
old_import = "import com.youkeda.exercise.claw.ai.voice.VoiceService;"
new_import = """import com.youkeda.exercise.claw.ai.voice.VoiceClient;
import com.youkeda.exercise.claw.ai.voice.VoiceService;"""
content = content.replace(old_import, new_import)
print('Added VoiceClient import')

# 2. 加 VoiceClient 字段和构造函数参数
old_fields = """    private final AiRoleRepository roles;
    private final AuthenticatedUser users;
    private final UserExecutionContext context;
    private final VoiceService voiceService;

    public AiRoleController(AiRoleRepository roles, AuthenticatedUser users, UserExecutionContext context, VoiceService voiceService) {
        this.roles = roles;
        this.users = users;
        this.context = context;
        this.voiceService = voiceService;
    }"""
new_fields = """    private final AiRoleRepository roles;
    private final AuthenticatedUser users;
    private final UserExecutionContext context;
    private final VoiceService voiceService;
    private final VoiceClient voiceClient;

    public AiRoleController(AiRoleRepository roles, AuthenticatedUser users, UserExecutionContext context, VoiceService voiceService, VoiceClient voiceClient) {
        this.roles = roles;
        this.users = users;
        this.context = context;
        this.voiceService = voiceService;
        this.voiceClient = voiceClient;
    }"""
content = content.replace(old_fields, new_fields)
print('Added VoiceClient field and constructor param')

# 3. 修改 uploadVoice 方法，上传到 DashScope 获取公网 URL
old_upload = """    /**
     * 上传角色声音样本（3-10秒音频），上传到 DashScope 后保存 file_url 到角色
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
                // 用 VoiceClient 的 ASR 上传逻辑上传音频到 DashScope，获取 file_url
                // 这里直接调用 voiceService 内部的上传，但是 voiceService 没有暴露上传方法
                // 所以我们用一个简单的方式：直接存 base64，TTS 时再处理
                // 实际上 DashScope cosyvoice 的 prompt_audio 需要是一个可访问的 URL
                // 我们先把音频存到本地，然后提供一个静态 URL
                String voiceDir = System.getProperty("user.dir") + "/voices";
                java.io.File dir = new java.io.File(voiceDir);
                if (!dir.exists()) dir.mkdirs();
                String fileName = id + "_" + System.currentTimeMillis() + ".mp3";
                java.io.File voiceFile = new java.io.File(voiceDir, fileName);
                file.transferTo(voiceFile);
                String voiceUrl = "/voices/" + fileName;
                // 更新角色的 voiceAudioUrl
                return roles.update(role.id(), role.name(), role.avatar(), role.personality(),
                        role.background(), role.speakingStyle(), role.catchphrase(), voiceUrl)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "更新角色声音失败"));
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "声音上传失败: " + e.getMessage());
            }
        });
    }"""
new_upload = """    /**
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
content = content.replace(old_upload, new_upload)
print('Modified uploadVoice to upload to DashScope')

# 4. 修改 TTS 方法，用角色的 voiceAudioUrl 做声音克隆
old_tts = """    @PostMapping("/{id}/tts")
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
                    // 角色有自定义声音时，用 DashScope 的公网 URL 做声音克隆
                    promptAudioUrl = roleOpt.get().voiceAudioUrl();
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
print('Modified TTS to use role voiceAudioUrl for voice cloning')

with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleController.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('AiRoleController.java fixed - voice cloning fully implemented')
