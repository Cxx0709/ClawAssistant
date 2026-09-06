with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleController.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. RoleRequest 加 voiceId 字段
old_record = '''    public record RoleRequest(
            String name,
            String avatar,
            String personality,
            String background,
            String speakingStyle,
            String catchphrase,
            String voiceAudioUrl
    ) {}'''
new_record = '''    public record RoleRequest(
            String name,
            String avatar,
            String personality,
            String background,
            String speakingStyle,
            String catchphrase,
            String voiceAudioUrl,
            String voiceId
    ) {}'''
content = content.replace(old_record, new_record)
print('Added voiceId to RoleRequest')

# 2. update 方法里，如果 voiceId 不为空，就更新 voiceId
old_update = '''    @PutMapping("/{id}")
    public AiRole update(Authentication authentication, @PathVariable String id, @RequestBody RoleRequest request) {
        validate(request);
        return scoped(authentication, () -> roles.update(
                id, request.name(), request.avatar(), request.personality(),
                request.background(), request.speakingStyle(), request.catchphrase(),
                request.voiceAudioUrl())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在")));
    }'''
new_update = '''    @PutMapping("/{id}")
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
    }'''
content = content.replace(old_update, new_update)
print('Modified update method to support voiceId')

with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleController.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('AiRoleController.java fixed - voiceId update support added')
