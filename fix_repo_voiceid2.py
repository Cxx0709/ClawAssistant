import re

with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleRepository.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 建表语句加 voice_id 字段（用正则替换，避免三引号冲突）
content = re.sub(
    r'(voice_audio_url TEXT,)\s*(created_at\s+INTEGER NOT NULL,)',
    r'\1\n                    voice_id       TEXT,\n                    \2',
    content
)
print('Added voice_id to create table')

# 2. 在 voice_audio_url 幂等迁移后面加 voice_id 幂等迁移
old_migration = '''        // 幂等迁移：给已有的表加 voice_audio_url 列
        try {
            jdbc.execute("ALTER TABLE ai_role ADD COLUMN voice_audio_url TEXT");
        } catch (Exception ignored) {
            // 列已存在，忽略
        }'''
new_migration = '''        // 幂等迁移：给已有的表加 voice_audio_url 列
        try {
            jdbc.execute("ALTER TABLE ai_role ADD COLUMN voice_audio_url TEXT");
        } catch (Exception ignored) {
            // 列已存在，忽略
        }
        // 幂等迁移：给已有的表加 voice_id 列
        try {
            jdbc.execute("ALTER TABLE ai_role ADD COLUMN voice_id TEXT");
        } catch (Exception ignored) {
            // 列已存在，忽略
        }'''
content = content.replace(old_migration, new_migration)
print('Added voice_id migration')

# 3. 所有 SELECT 查询加 voice_id 字段
content = content.replace(
    'voice_audio_url, created_at, updated_at',
    'voice_audio_url, voice_id, created_at, updated_at'
)
print('Added voice_id to SELECT queries')

# 4. create 方法 INSERT 加 voice_id 字段
content = content.replace(
    'INSERT INTO ai_role(id, user_id, name, avatar, personality, background, speaking_style, catchphrase, voice_audio_url, created_at, updated_at)',
    'INSERT INTO ai_role(id, user_id, name, avatar, personality, background, speaking_style, catchphrase, voice_audio_url, voice_id, created_at, updated_at)'
)
content = content.replace(
    'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
    'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)'
)
# create 方法里的参数和返回值加 null
content = content.replace(
    'id, userId, name, avatar, personality, background, speakingStyle, catchphrase, voiceAudioUrl, now, now);\n        return new AiRole(id, userId, name, avatar, personality, background, speakingStyle, catchphrase, voiceAudioUrl, now, now);',
    'id, userId, name, avatar, personality, background, speakingStyle, catchphrase, voiceAudioUrl, null, now, now);\n        return new AiRole(id, userId, name, avatar, personality, background, speakingStyle, catchphrase, voiceAudioUrl, null, now, now);'
)
print('Added voice_id to create method')

# 5. 在 update 方法后面加 updateVoiceId 方法
old_update_end = '''        if (updated == 0) return Optional.empty();
        return findById(id);
    }

    public boolean delete'''
new_update_end = '''        if (updated == 0) return Optional.empty();
        return findById(id);
    }

    /**
     * 只更新角色的 voice_id（声音克隆创建成功后调用）
     */
    public Optional<AiRole> updateVoiceId(String id, String voiceId) {
        long now = System.currentTimeMillis() / 1000;
        int updated = jdbc.update("UPDATE ai_role SET voice_id = ?, updated_at = ? WHERE id = ?",
                voiceId, now, id);
        if (updated == 0) return Optional.empty();
        return findById(id);
    }

    public boolean delete'''
content = content.replace(old_update_end, new_update_end)
print('Added updateVoiceId method')

# 6. map 方法加 voice_id
old_map = '''        String voiceAudioUrl = null;
        try {
            voiceAudioUrl = rs.getString("voice_audio_url");
        } catch (Exception ignored) {
            // 兼容旧表结构
        }
        return new AiRole(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("name"),
                rs.getString("avatar"),
                rs.getString("personality"),
                rs.getString("background"),
                rs.getString("speaking_style"),
                rs.getString("catchphrase"),
                voiceAudioUrl,
                rs.getLong("created_at"),
                rs.getLong("updated_at"));'''
new_map = '''        String voiceAudioUrl = null;
        String voiceId = null;
        try {
            voiceAudioUrl = rs.getString("voice_audio_url");
        } catch (Exception ignored) {
            // 兼容旧表结构
        }
        try {
            voiceId = rs.getString("voice_id");
        } catch (Exception ignored) {
            // 兼容旧表结构
        }
        return new AiRole(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("name"),
                rs.getString("avatar"),
                rs.getString("personality"),
                rs.getString("background"),
                rs.getString("speaking_style"),
                rs.getString("catchphrase"),
                voiceAudioUrl,
                voiceId,
                rs.getLong("created_at"),
                rs.getLong("updated_at"));'''
content = content.replace(old_map, new_map)
print('Added voice_id to map method')

with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleRepository.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('AiRoleRepository.java fixed - voice_id support added')
