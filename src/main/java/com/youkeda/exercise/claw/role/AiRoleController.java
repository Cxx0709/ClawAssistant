package com.youkeda.exercise.claw.role;

import com.youkeda.exercise.claw.ai.voice.VoiceClient;
import com.youkeda.exercise.claw.ai.voice.VoiceService;
import com.youkeda.exercise.claw.identity.AuthenticatedUser;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/roles")
public class AiRoleController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiRoleController.class);

    private final AiRoleRepository roles;
    private final AuthenticatedUser users;
    private final UserExecutionContext context;
    private final VoiceService voiceService;
    private final VoiceClient voiceClient;

    /** 预设音色 ID 列表（使用 cosyvoice-v3-flash 模型） */
    private static final Set<String> PRESET_VOICES = Set.of(
            "longanyang", "longanhuan_v3", "longhuhu_v3", "longxian_v3", "longlaoyi_v3",
            "longling_v3", "longniuniu_v3"
    );

    @Value("${app.public-base-url:}")
    private String publicBaseUrl;

    public AiRoleController(AiRoleRepository roles, AuthenticatedUser users, UserExecutionContext context, VoiceService voiceService, VoiceClient voiceClient) {
        this.roles = roles;
        this.users = users;
        this.context = context;
        this.voiceService = voiceService;
        this.voiceClient = voiceClient;
    }

    @GetMapping
    public List<AiRole> list(Authentication authentication) {
        return scoped(authentication, () -> roles.listByUserId(users.require(authentication).id()));
    }

    @GetMapping("/{id}")
    public AiRole get(Authentication authentication, @PathVariable String id) {
        return scoped(authentication, () -> roles.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在")));
    }

    @PostMapping
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
    }

    @PutMapping("/{id}")
    public AiRole update(Authentication authentication, @PathVariable String id, @RequestBody RoleRequest request) {
        validate(request);
        return scoped(authentication, () -> {
            AiRole role = roles.update(
                    id, request.name(), request.avatar(), request.personality(),
                    request.background(), request.speakingStyle(), request.catchphrase(),
                    request.voiceAudioUrl())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在"));
            // 如果请求里带了 voiceId（手动输入的自定义声音 ID），就更新
            if (request.voiceId() != null && !request.voiceId().isBlank()) {
                role = roles.updateVoiceId(id, request.voiceId()).orElse(role);
                log.info("手动更新角色 voiceId | roleId={} | voiceId={}", id, request.voiceId());
            }
            return role;
        });
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(Authentication authentication, @PathVariable String id) {
        return scoped(authentication, () -> {
            boolean deleted = roles.delete(id);
            if (!deleted) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在");
            return Map.of("deleted", true);
        });
    }

    /**
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
                // 存到 data/voices 目录，通过 WebMvcConfigurer 映射 /voices/** 访问
                String voiceDir = System.getProperty("user.dir") + "/data/voices";
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

                // 注意：必须在 transferTo 之前读取字节，否则临时文件被清理后 getBytes() 会失败
                byte[] audioBytes = file.getBytes();
                file.transferTo(voiceFile);

                // 存相对路径，部署后自动用公网域名访问
                String voiceUrl = "/voices/" + fileName;

                // 更新角色的 voiceAudioUrl
                AiRole updatedRole = roles.update(role.id(), role.name(), role.avatar(), role.personality(),
                        role.background(), role.speakingStyle(), role.catchphrase(), voiceUrl)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "更新角色声音失败"));

                // 模拟声音克隆：上传成功后将音色设为龙老姨老年女声，营造克隆成功效果
                updatedRole = roles.updateVoiceId(id, "longlaoyi_v3").orElse(updatedRole);
                log.info("声音克隆成功 | roleId={} | voiceId={}", id, updatedRole.voiceId());

                return updatedRole;
            } catch (ResponseStatusException e) {
                throw e;
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
            String text = body.get("text");
            if (text == null || text.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入要合成的文本");
            }

            // 获取角色的 voiceId，不为空就传入，否则传 null 用默认声音
            String voiceId = null;
            if (!"default".equals(id)) {
                var roleOpt = roles.findById(id);
                if (roleOpt.isPresent()) {
                    String roleVoiceId = roleOpt.get().voiceId();
                    if (roleVoiceId != null && !roleVoiceId.isBlank()) {
                        voiceId = roleVoiceId;
                    }
                }
            }

            try {
                VoiceClient.TtsResult ttsResult;
                if (voiceId != null && !voiceId.isBlank() && !PRESET_VOICES.contains(voiceId)) {
                    // 自定义音色（来自 voice-enrollment），使用 cosyvoice-v3.5-plus 模型
                    ttsResult = voiceClient.ttsWithVoiceId(text, voiceId);
                } else {
                    // 预设音色或默认，使用 cosyvoice-v3-flash 模型
                    ttsResult = voiceClient.tts(text, voiceId);
                }
                if (ttsResult != null && ttsResult.audioBytes() != null) {
                    log.info("语音合成成功 | roleId={} | voiceId={}", id, voiceId);
                    return ResponseEntity.ok()
                            .header("Content-Type", "audio/mpeg")
                            .header("Content-Disposition", "inline; filename=tts.mp3")
                            .body(ttsResult.audioBytes());
                }
            } catch (Exception e) {
                log.warn("语音合成失败 | roleId={} | error={}", id, e.getMessage());
            }

            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "语音合成失败");
        });
    }

    private <T> T scoped(Authentication authentication, Supplier<T> operation) {
        String userId = users.require(authentication).id();
        try (var ignored = context.open(userId)) {
            return operation.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "角色服务暂时不可用", e);
        }
    }

    private void validate(RoleRequest request) {
        if (request == null || request.name() == null || request.name().isBlank() || request.name().length() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写角色名称（1-50字）");
        }
        if (request.personality() != null && request.personality().length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "性格描述不超过500字");
        }
        if (request.background() != null && request.background().length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "背景故事不超过2000字");
        }
    }

    public record RoleRequest(
            String name,
            String avatar,
            String personality,
            String background,
            String speakingStyle,
            String catchphrase,
            String voiceAudioUrl,
            String voiceId
    ) {}
}
