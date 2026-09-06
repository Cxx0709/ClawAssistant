with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleController.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 修改 uploadVoice 方法：上传后自动创建自定义声音
old_upload = '''                // 存相对路径，部署后自动用公网域名访问
                String voiceUrl = "/voices/" + fileName;

                // 更新角色的 voiceAudioUrl
                return roles.update(role.id(), role.name(), role.avatar(), role.personality(),
                        role.background(), role.speakingStyle(), role.catchphrase(), voiceUrl)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "更新角色声音失败"));'''
new_upload = '''                // 存相对路径，部署后自动用公网域名访问
                String voiceUrl = "/voices/" + fileName;

                // 更新角色的 voiceAudioUrl
                AiRole updatedRole = roles.update(role.id(), role.name(), role.avatar(), role.personality(),
                        role.background(), role.speakingStyle(), role.catchphrase(), voiceUrl)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "更新角色声音失败"));

                // 自动创建自定义声音（声音复刻）
                // 需要公网可访问的音频 URL，拼接 publicBaseUrl（ngrok 或服务器域名）
                if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
                    String publicAudioUrl = publicBaseUrl + voiceUrl;
                    // prefix 只能是数字/字母，<=10 字符，用角色 id 前 8 位
                    String prefix = "role" + role.id().replace("-", "").substring(0, 6);
                    try {
                        String voiceId = voiceClient.createVoice(publicAudioUrl, prefix);
                        if (voiceId != null && !voiceId.isBlank()) {
                            // 保存 voice_id 到角色
                            updatedRole = roles.updateVoiceId(role.id(), voiceId)
                                    .orElse(updatedRole);
                            log.info("自定义声音创建成功 | roleId={} | voiceId={}", role.id(), voiceId);
                        } else {
                            log.warn("自定义声音创建返回空 voice_id | roleId={}", role.id());
                        }
                    } catch (Exception e) {
                        // 创建声音失败不影响上传，记录日志即可
                        log.warn("自动创建自定义声音失败（不影响上传） | roleId={} | error={}", role.id(), e.getMessage());
                    }
                } else {
                    log.info("未配置公网 URL，跳过自动创建自定义声音 | roleId={}", role.id());
                }

                return updatedRole;'''
content = content.replace(old_upload, new_upload)
print('Modified uploadVoice to auto-create custom voice')

# 2. 修改 tts 方法：用 voice_id 合成，移除 promptAudioUrl 逻辑
old_tts = '''        return scoped(authentication, () -> {
            String text = body.get("text");
            if (text == null || text.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入要合成的文本");
            }
            // 'default' 或找不到角色时用默认声音
            String promptAudioUrl = null;
            if (!"default".equals(id)) {
                var roleOpt = roles.findById(id);
                if (roleOpt.isPresent()) {
                    String voiceUrl = roleOpt.get().voiceAudioUrl();
                    if (voiceUrl != null && !voiceUrl.isBlank()) {
                        // 如果是相对路径（/voices/xxx），拼接公网基础 URL（ngrok 或服务器域名）
                        if (voiceUrl.startsWith("/") && publicBaseUrl != null && !publicBaseUrl.isBlank()) {
                            promptAudioUrl = publicBaseUrl + voiceUrl;
                        }
                        // 如果已经是公网 URL，直接用
                        else if (voiceUrl.startsWith("http://") || voiceUrl.startsWith("https://")) {
                            promptAudioUrl = voiceUrl;
                        }
                    }
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
        });'''
new_tts = '''        return scoped(authentication, () -> {
            String text = body.get("text");
            if (text == null || text.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入要合成的文本");
            }

            byte[] audioBytes = null;

            // 'default' 或找不到角色时用默认声音
            if (!"default".equals(id)) {
                var roleOpt = roles.findById(id);
                if (roleOpt.isPresent()) {
                    String voiceId = roleOpt.get().voiceId();
                    // 如果角色有自定义声音 ID，用自定义声音合成
                    if (voiceId != null && !voiceId.isBlank()) {
                        try {
                            VoiceClient.TtsResult ttsResult = voiceClient.ttsWithVoiceId(text, voiceId);
                            if (ttsResult != null && ttsResult.audioBytes() != null) {
                                audioBytes = ttsResult.audioBytes();
                                log.info("使用自定义声音合成成功 | roleId={} | voiceId={}", id, voiceId);
                            }
                        } catch (Exception e) {
                            log.warn("使用自定义声音合成失败，回退默认声音 | roleId={} | error={}", id, e.getMessage());
                        }
                    }
                }
            }

            // 回退到默认声音
            if (audioBytes == null) {
                VoiceService.VoiceSynthesisResult result = voiceService.synthesize(text);
                if (result != null && result.getAudioBytes() != null) {
                    audioBytes = result.getAudioBytes();
                }
            }

            if (audioBytes == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "语音合成失败");
            }
            return ResponseEntity.ok()
                    .header("Content-Type", "audio/mpeg")
                    .header("Content-Disposition", "inline; filename=tts.mp3")
                    .body(audioBytes);
        });'''
content = content.replace(old_tts, new_tts)
print('Modified tts to use voice_id')

# 3. 加 log 字段（如果还没有的话）
if 'private static final Logger log' not in content:
    # 在类定义后面加 log 字段
    old_class = '''public class AiRoleController {

    private final AiRoleRepository roles;'''
    new_class = '''public class AiRoleController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiRoleController.class);

    private final AiRoleRepository roles;'''
    content = content.replace(old_class, new_class)
    print('Added log field')

with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleController.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('AiRoleController.java fixed - voice_id support added')
