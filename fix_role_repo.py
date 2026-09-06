with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleRepository.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 建表语句加 voice_audio_url 字段 + 幂等迁移
old_init = '''    @PostConstruct
    void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_role (
                    id             TEXT PRIMARY KEY,
                    user_id        TEXT NOT NULL,
                    name           TEXT NOT NULL,
                    avatar         TEXT,
                    personality    TEXT,
                    background     TEXT,
                    speaking_style TEXT,
                    catchphrase    TEXT,
                    created_at     INTEGER NOT NULL,
                    updated_at     INTEGER NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ai_role_user ON ai_role(user_id, updated_at DESC)");
    }'''
new_init = '''    @PostConstruct
    void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_role (
                    id             TEXT PRIMARY KEY,
                    user_id        TEXT NOT NULL,
                    name           TEXT NOT NULL,
                    avatar         TEXT,
                    personality    TEXT,
                    background     TEXT,
                    speaking_style TEXT,
                    catchphrase    TEXT,
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
        }
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ai_role_user ON ai_role(user_id, updated_at DESC)");
    }'''
content = content.replace(old_init, new_init)

# 2. listByUserId 的 SELECT 加 voice_audio_url
old_list_select = '''        return jdbc.query("""
                SELECT id, user_id, name, avatar, personality, background, speaking_style, catchphrase, created_at, updated_at
                FROM ai_role WHERE user_id = ? ORDER BY updated_at DESC
                """, (rs, rowNum) -> map(rs), userId);'''
new_list_select = '''        return jdbc.query("""
                SELECT id, user_id, name, avatar, personality, background, speaking_style, catchphrase, voice_audio_url, created_at, updated_at
                FROM ai_role WHERE user_id = ? ORDER BY updated_at DESC
                """, (rs, rowNum) -> map(rs), userId);'''
content = content.replace(old_list_select, new_list_select)

# 3. findById 的 SELECT 加 voice_audio_url
old_find_select = '''        List<AiRole> roles = jdbc.query("""
                SELECT id, user_id, name, avatar, personality, background, speaking_style, catchphrase, created_at, updated_at
                FROM ai_role WHERE id = ?
                """, (rs, rowNum) -> map(rs), id);'''
new_find_select = '''        List<AiRole> roles = jdbc.query("""
                SELECT id, user_id, name, avatar, personality, background, speaking_style, catchphrase, voice_audio_url, created_at, updated_at
                FROM ai_role WHERE id = ?
                """, (rs, rowNum) -> map(rs), id);'''
content = content.replace(old_find_select, new_find_select)

# 4. create 方法加 voiceAudioUrl 参数
old_create = '''    public AiRole create(String userId, String name, String avatar, String personality,
                          String background, String speakingStyle, String catchphrase) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis() / 1000;
        jdbc.update("""
                INSERT INTO ai_role(id, user_id, name, avatar, personality, background, speaking_style, catchphrase, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, userId, name, avatar, personality, background, speakingStyle, catchphrase, now, now);
        return new AiRole(id, userId, name, avatar, personality, background, speakingStyle, catchphrase, now, now);
    }'''
new_create = '''    public AiRole create(String userId, String name, String avatar, String personality,
                          String background, String speakingStyle, String catchphrase, String voiceAudioUrl) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis() / 1000;
        jdbc.update("""
                INSERT INTO ai_role(id, user_id, name, avatar, personality, background, speaking_style, catchphrase, voice_audio_url, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, userId, name, avatar, personality, background, speakingStyle, catchphrase, voiceAudioUrl, now, now);
        return new AiRole(id, userId, name, avatar, personality, background, speakingStyle, catchphrase, voiceAudioUrl, now, now);
    }'''
content = content.replace(old_create, new_create)

# 5. update 方法加 voiceAudioUrl 参数
old_update = '''    public Optional<AiRole> update(String id, String name, String avatar, String personality,
                                    String background, String speakingStyle, String catchphrase) {
        long now = System.currentTimeMillis() / 1000;
        int updated = jdbc.update("""
                UPDATE ai_role SET name = ?, avatar = ?, personality = ?, background = ?, speaking_style = ?, catchphrase = ?, updated_at = ?
                WHERE id = ?
                """, name, avatar, personality, background, speakingStyle, catchphrase, now, id);
        if (updated == 0) return Optional.empty();
        return findById(id);
    }'''
new_update = '''    public Optional<AiRole> update(String id, String name, String avatar, String personality,
                                    String background, String speakingStyle, String catchphrase, String voiceAudioUrl) {
        long now = System.currentTimeMillis() / 1000;
        int updated = jdbc.update("""
                UPDATE ai_role SET name = ?, avatar = ?, personality = ?, background = ?, speaking_style = ?, catchphrase = ?, voice_audio_url = ?, updated_at = ?
                WHERE id = ?
                """, name, avatar, personality, background, speakingStyle, catchphrase, voiceAudioUrl, now, id);
        if (updated == 0) return Optional.empty();
        return findById(id);
    }'''
content = content.replace(old_update, new_update)

# 6. map 方法加 voice_audio_url
old_map = '''    private static AiRole map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AiRole(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("name"),
                rs.getString("avatar"),
                rs.getString("personality"),
                rs.getString("background"),
                rs.getString("speaking_style"),
                rs.getString("catchphrase"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }'''
new_map = '''    private static AiRole map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String voiceAudioUrl = null;
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
                rs.getLong("updated_at"));
    }'''
content = content.replace(old_map, new_map)

with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleRepository.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('AiRoleRepository.java fixed - added voice_audio_url field')
