package com.youkeda.exercise.claw.domain.anime;

/**
 * 番剧播出事件。
 * 表示某部番剧某一集的播出时间，与 Anime 为多对一关系。
 */
public class AnimeEpisode {

    private int anilistId;
    private int episode;          // 第几集
    private long airingAt;        // 播出时间（Unix 秒）

    public AnimeEpisode() {}

    public AnimeEpisode(int anilistId, int episode, long airingAt) {
        this.anilistId = anilistId;
        this.episode = episode;
        this.airingAt = airingAt;
    }

    public int getAnilistId() { return anilistId; }
    public void setAnilistId(int anilistId) { this.anilistId = anilistId; }

    public int getEpisode() { return episode; }
    public void setEpisode(int episode) { this.episode = episode; }

    public long getAiringAt() { return airingAt; }
    public void setAiringAt(long airingAt) { this.airingAt = airingAt; }
}
