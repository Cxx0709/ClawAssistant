package com.youkeda.exercise.claw.feature.anime.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.domain.anime.Anime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
@ConditionalOnProperty(name = "storage.enabled", havingValue = "true")
public class AnimeSubscriptionStore {

    private static final Logger log = LoggerFactory.getLogger(AnimeSubscriptionStore.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AnimeSubscriptionStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void subscribe(Anime anime) {
        String genresJson = "[]";
        if (anime.getGenres() != null) {
            try { genresJson = objectMapper.writeValueAsString(anime.getGenres()); }
            catch (JsonProcessingException e) { log.warn("序列化 genres 失败", e); }
        }
        long now = System.currentTimeMillis() / 1000;
        jdbc.update("""
            INSERT OR IGNORE INTO anime_subscription
            (anilist_id, title, title_ja, cover_url, status, genres, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, anime.getAnilistId(), anime.getTitle(), anime.getTitleJa(),
            anime.getCoverUrl(), anime.getStatus(), genresJson, now);
    }

    public void unsubscribe(int anilistId) {
        jdbc.update("DELETE FROM anime_subscription WHERE anilist_id = ?", anilistId);
    }

    public List<Anime> listAll() {
        return jdbc.query("SELECT * FROM anime_subscription ORDER BY created_at DESC",
            new AnimeRowMapper());
    }

    public Anime findByAnilistId(int anilistId) {
        try {
            return jdbc.queryForObject(
                "SELECT * FROM anime_subscription WHERE anilist_id = ?",
                new AnimeRowMapper(), anilistId);
        } catch (Exception e) {
            return null;
        }
    }

    public List<Anime> getCurrentlyAiring() {
        return jdbc.query(
            "SELECT * FROM anime_subscription WHERE status = 'RELEASING'",
            new AnimeRowMapper());
    }

    private class AnimeRowMapper implements RowMapper<Anime> {
        @Override
        public Anime mapRow(ResultSet rs, int rowNum) throws SQLException {
            Anime anime = new Anime();
            anime.setAnilistId(rs.getInt("anilist_id"));
            anime.setTitle(rs.getString("title"));
            anime.setTitleJa(rs.getString("title_ja"));
            anime.setCoverUrl(rs.getString("cover_url"));
            anime.setStatus(rs.getString("status"));
            // 反序列化 genres
            String genresStr = rs.getString("genres");
            if (genresStr != null && !genresStr.isBlank()) {
                try {
                    List<String> genres = objectMapper.readValue(genresStr,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    anime.setGenres(genres);
                } catch (Exception e) {
                    log.warn("反序列化 genres 失败", e);
                }
            }
            return anime;
        }
    }
}
