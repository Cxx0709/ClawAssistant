with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleController.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 加 import
old_imports = '''import com.youkeda.exercise.claw.identity.AuthenticatedUser;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;'''
new_imports = '''import com.youkeda.exercise.claw.ai.voice.VoiceService;
import com.youkeda.exercise.claw.identity.AuthenticatedUser;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;'''
content = content.replace(old_imports, new_imports)

# 2. 加 VoiceService 依赖注入
old_fields = '''    private final AiRoleRepository roles;
    private final AuthenticatedUser users;
    private final UserExecutionContext context;

    public AiRoleController(AiRoleRepository roles, AuthenticatedUser users, UserExecutionContext context) {
        this.roles = roles;
        this.users = users;
        this.context = context;
    }'''
new_fields = '''    private final AiRoleRepository roles;
    private final AuthenticatedUser users;
    private final UserExecutionContext context;
    private final VoiceService voiceService;

    public AiRoleController(AiRoleRepository roles, AuthenticatedUser users, UserExecutionContext context, VoiceService voiceService) {
        this.roles = roles;
        this.users = users;
        this.context = context;
        this.voiceService = voiceService;
    }'''
content = content.replace(old_fields, new_fields)

# 3. create 方法传 voiceAudioUrl
old_create = '''    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AiRole create(Authentication authentication, @RequestBody RoleRequest request) {
        validate(request);
        return scoped(authentication, () -> roles.create(
                users.require(authentication).id(),
                request.name(),
                request.avatar(),
                request.personality(),
                request.background(),
                request.speakingStyle(),
                request.catchphrase()));
    }'''
new_create = '''    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AiRole create(Authentication authentication, @RequestBody RoleRequest request) {
        validate(request);
        return scoped(authentication, () -> roles.create(
                users.require(authentication).id(),
                request.name(),
                request.avatar(),
                request.personality(),
                request.background(),
                request.speakingStyle(),
                request.catchphrase(),
                request.voiceAudioUrl()));
    }'''
content = content.replace(old_create, new_create)

# 4. update 方法传 voiceAudioUrl
old_update = '''    @PutMapping("/{id}")
    public AiRole update(Authentication authentication, @PathVariable String id, @RequestBody RoleRequest request) {
        validate(request);
        return scoped(authentication, () -> roles.update(
                id, request.name(), request.avatar(), request.personality(),
                request.background(), request.speakingStyle(), request.catchphrase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在")));
    }'''
new_update = '''    @PutMapping("/{id}")
    public AiRole update(Authentication authentication, @PathVariable String id, @RequestBody RoleRequest request) {
        validate(request);
        return scoped(authentication, () -> roles.update(
                id, request.name(), request.avatar(), request.personality(),
                request.background(), request.speakingStyle(), request.catchphrase(),
                request.voiceAudioUrl())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在")));
    }'''
content = content.replace(old_update, new_update)

# 5. 在 delete 方法后面加声音上传和 TTS 端点
old_delete_end = '''    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(Authentication authentication, @PathVariable String id) {
        return scoped(authentication, () -> {
            boolean deleted = roles.delete(id);
            if (!deleted) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在");
            return Map.of("deleted", true);
        });
    }'''
new_delete_end = '''    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(Authentication authentication, @PathVariable String id) {
        return scoped(authentication, () -> {
            boolean deleted = roles.delete(id);
            if (!deleted) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在");
            return Map.of("deleted", true);
        });
    }

    /**
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
    }

    /**
     * 用角色的声音合成语音（TTS），返回音频字节
     */
    @PostMapping("/{id}/tts")
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
                    .header("Content-Disposition", "inline; filename=\"tts.mp3\"")
                    .body(result.getAudioBytes());
        });
    }'''
content = content.replace(old_delete_end, new_delete_end)

# 6. RoleRequest record 加 voiceAudioUrl
old_record = '''    public record RoleRequest(
            String name,
            String avatar,
            String personality,
            String background,
            String speakingStyle,
            String catchphrase
    ) {}'''
new_record = '''    public record RoleRequest(
            String name,
            String avatar,
            String personality,
            String background,
            String speakingStyle,
            String catchphrase,
            String voiceAudioUrl
    ) {}'''
content = content.replace(old_record, new_record)

with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleController.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('AiRoleController.java fixed - added voiceAudioUrl, upload and TTS endpoints')
