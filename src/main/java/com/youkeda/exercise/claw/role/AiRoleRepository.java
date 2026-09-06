package com.youkeda.exercise.claw.role;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AiRoleRepository {

    private final JdbcTemplate jdbc;

    public AiRoleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
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
        }
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ai_role_user ON ai_role(user_id, updated_at DESC)");
    }

    public List<AiRole> listByUserId(String userId) {
        return jdbc.query("""
                SELECT id, user_id, name, avatar, personality, background, speaking_style, catchphrase, voice_audio_url, voice_id, created_at, updated_at
                FROM ai_role WHERE user_id = ? ORDER BY updated_at DESC
                """, (rs, rowNum) -> map(rs), userId);
    }

    public Optional<AiRole> findById(String id) {
        List<AiRole> roles = jdbc.query("""
                SELECT id, user_id, name, avatar, personality, background, speaking_style, catchphrase, voice_audio_url, voice_id, created_at, updated_at
                FROM ai_role WHERE id = ?
                """, (rs, rowNum) -> map(rs), id);
        return roles.stream().findFirst();
    }

    public AiRole create(String userId, String name, String avatar, String personality,
                          String background, String speakingStyle, String catchphrase, String voiceAudioUrl) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis() / 1000;
        jdbc.update("""
                INSERT INTO ai_role(id, user_id, name, avatar, personality, background, speaking_style, catchphrase, voice_audio_url, voice_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, userId, name, avatar, personality, background, speakingStyle, catchphrase, voiceAudioUrl, null, now, now);
        return new AiRole(id, userId, name, avatar, personality, background, speakingStyle, catchphrase, voiceAudioUrl, null, now, now);
    }

    public Optional<AiRole> update(String id, String name, String avatar, String personality,
                                    String background, String speakingStyle, String catchphrase, String voiceAudioUrl) {
        long now = System.currentTimeMillis() / 1000;
        int updated = jdbc.update("""
                UPDATE ai_role SET name = ?, avatar = ?, personality = ?, background = ?, speaking_style = ?, catchphrase = ?, voice_audio_url = ?, updated_at = ?
                WHERE id = ?
                """, name, avatar, personality, background, speakingStyle, catchphrase, voiceAudioUrl, now, id);
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
    }

    public boolean delete(String id) {
        return jdbc.update("DELETE FROM ai_role WHERE id = ?", id) > 0;
    }

    private static AiRole map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String voiceAudioUrl = null;
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
                rs.getLong("updated_at"));
    }
}
