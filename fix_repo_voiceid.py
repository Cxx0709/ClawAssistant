with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleRepository.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 建表语句加 voice_id 字段 + 幂等迁移
old_init = """                    catchphrase    TEXT,
                    voice_audio_url TEXT,
                    created_at     INTEGER NOT NULL,
                    updated_at     INTEGER NOT NULL
                )
                """);
        // 幂等迁移：给已有的表加 voice_audio_url 列
        try {
            jdbc.execute("ALTER TABLE ai_role ADD COLUMN voice_audio_url TEXT");
        } catch (Exception ignored) {
            // 列已存在，忽略
        }"""
new_init = """                    catchphrase    TEXT,
                    voice_audio_url TEXT,
                    voice_id       TEXT,
                    created_at     INTEGER NOT NULL,
                    updated_at     INTEGER NOT NULL
                )
                """);
        // 幂等迁移：给已有的表加 voice_audio_url 列
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
        }"""
content = content.replace(old_init, new_init)
print('Added voice_id to init')

# 2. listByUserId 的 SELECT 加 voice_id
old_list_select = """                SELECT id, user_id, name, avatar, personality, background, speaking_style, catchphrase, voice_audio_url, created_at, updated_at
                FROM ai_role WHERE user_id = ? ORDER BY updated_at DESC"""
new_list_select = """                SELECT id, user_id, name, avatar, personality, background, speaking_style, catchphrase, voice_audio_url, voice_id, created_at, updated_at
                FROM ai_role WHERE user_id = ? ORDER BY updated_at DESC"""
content = content.replace(old_list_select, new_list_select)
print('Added voice_id to listByUserId select')

# 3. findById 的 SELECT 加 voice_id
old_find_select = """                SELECT id, user_id, name, avatar, personality, background, speaking_style, catchphrase, voice_audio_url, created_at, updated_at
                FROM ai_role WHERE id = ?"""
new_find_select = """                SELECT id, user_id, name, avatar, personality, background, speaking_style, catchphrase, voice_audio_url, voice_id, created_at, updated_at
                FROM ai_role WHERE id = ?"""
content = content.replace(old_find_select, new_find_select)
print('Added voice_id to findById select')

# 4. create 方法加 voiceId 参数
old_create = """    public AiRole create(String userId, String name, String avatar, String personality,
                          String background, String speakingStyle, String catchphrase, String voiceAudioUrl) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis() / 1000;
        jdbc.update(\"\"\"
                INSERT INTO ai_role(id, user_id, name, avatar, personality, background, speaking_style, catchphrase, voice_audio_url, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                \"\"\", id, userId, name, avatar, personality, background, speakingStyle, catchphrase, voiceAudioUrl, now, now);
        return new AiRole(id, userId, name, avatar, personality, background, speakingStyle, catchphrase, voiceAudioUrl, now, now);
    }"""
new_create = """    public AiRole create(String userId, String name, String avatar, String personality,
                          String background, String speakingStyle, String catchphrase, String voiceAudioUrl) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis() / 1000;
        jdbc.update(\"\"\"
                INSERT INTO ai_role(id, user_id, name, avatar, personality, background, speaking_style, catchphrase, voice_audio_url, voice_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                \"\"\", id, userId, name, avatar, personality, background, speakingStyle, catchphrase, voiceAudioUrl, null, now, now);
        return new AiRole(id, userId, name, avatar, personality, background, speakingStyle, catchphrase, voiceAudioUrl, null, now, now);
    }"""
content = content.replace(old_create, new_create)
print('Added voice_id to create')

# 5. update 方法加 voiceId 参数
old_update = """    public Optional<AiRole> update(String id, String name, String avatar, String personality,
                                    String background, String speakingStyle, String catchphrase, String voiceAudioUrl) {
        long now = System.currentTimeMillis() / 1000;
        int updated = jdbc.update(\"\"\"
                UPDATE ai_role SET name = ?, avatar = ?, personality = ?, background = ?, speaking_style = ?, catchphrase = ?, voice_audio_url = ?, updated_at = ?
                WHERE id = ?
                \"\"\", name, avatar, personality, background, speakingStyle, catchphrase, voiceAudioUrl, now, id);
        if (updated == 0) return Optional.empty();
        return findById(id);
    }"""
new_update = """    public Optional<AiRole> update(String id, String name, String avatar, String personality,
                                    String background, String speakingStyle, String catchphrase, String voiceAudioUrl) {
        long now = System.currentTimeMillis() / 1000;
        int updated = jdbc.update(\"\"\"
                UPDATE ai_role SET name = ?, avatar = ?, personality = ?, background = ?, speaking_style = ?, catchphrase = ?, voice_audio_url = ?, updated_at = ?
                WHERE id = ?
                \"\"\", name, avatar, personality, background, speakingStyle, catchphrase, voiceAudioUrl, now, id);
        if (updated == 0) return Optional.empty();
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
    }"""
content = content.replace(old_update, new_update)
print('Added updateVoiceId method')

# 6. map 方法加 voice_id
old_map = """        String voiceAudioUrl = null;
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
                rs.getLong("updated_at"));"""
new_map = """        String voiceAudioUrl = null;
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
                rs.getLong("updated_at"));"""
content = content.replace(old_map, new_map)
print('Added voice_id to map')

with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleRepository.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('AiRoleRepository.java fixed - voice_id support added')
