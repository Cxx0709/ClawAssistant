package com.youkeda.exercise.claw.feature.anime.notification;

import com.youkeda.exercise.claw.ai.llm.LLMClient;
import com.youkeda.exercise.claw.domain.anime.Anime;
import com.youkeda.exercise.claw.feature.anime.client.AniListClient;
import com.youkeda.exercise.claw.feature.anime.store.AnimeSubscriptionStore;
import com.youkeda.exercise.claw.notification.NotificationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AnimeSeasonSource 季度推荐推送测试
 *
 * <p>修复背景：推送此前直接用 AniList 罗马音标题拼列表（无中文译名、无换行）。
 * 现在通过 LLM 生成中文译名、逐条换行的推荐内容，失败时回退罗马音逐条列表。
 */
@ExtendWith(MockitoExtension.class)
class AnimeSeasonSourceTest {

    @Mock
    private AniListClient aniListClient;

    @Mock
    private AnimeSubscriptionStore subscriptionStore;

    @Mock
    private NotificationEventPublisher publisher;

    @Mock
    private LLMClient llmClient;

    private AnimeSeasonSource source;

    @BeforeEach
    void setUp() {
        source = new AnimeSeasonSource(aniListClient, subscriptionStore, publisher, llmClient);
    }

    private Anime anime(int id, String title, String titleJa, List<String> genres, int score) {
        return new Anime(id, title, titleJa, "", "RELEASING", 12, genres, score, 100000);
    }

    @Test
    @DisplayName("推送内容使用 LLM 生成的中文译名并逐条换行")
    void pushUsesChineseTitlesFromLlm() {
        when(aniListClient.getCurrentSeasonAnime(1)).thenReturn(List.of(
                anime(1, "Grand Blue Season 3", "ぐらんぶる", List.of("Comedy", "Slice of Life"), 82),
                anime(2, "Mushoku Tensei III", "無職転生", List.of("Fantasy"), 85)
        ));
        when(subscriptionStore.listAll()).thenReturn(List.of(
                anime(100, "Seihantai na Kimi to Boku", "正反対な君と僕", List.of("Comedy", "Romance"), 78)
        ));
        when(llmClient.chatWithSystemPrompt(anyString(), anyString(), anyInt()))
                .thenReturn("• 《碧蓝之海 第三季》Grand Blue Season 3（喜剧/日常）\n"
                        + "• 《无职转生 III》Mushoku Tensei III（奇幻）");

        source.check();

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(publisher).publish(eq("ANIME_SEASON"), anyString(), contentCaptor.capture(), anyInt());
        String content = contentCaptor.getValue();

        assertNotNull(content);
        assertTrue(content.contains("碧蓝之海"), "推送应包含中文译名，实际=" + content);
        assertTrue(content.contains("无职转生"), "推送应包含中文译名，实际=" + content);
        assertTrue(content.contains("\n"), "多条番剧应逐条换行，实际=" + content);
    }

    @Test
    @DisplayName("LLM 返回空时回退罗马音逐条列表（仍带换行）")
    void fallbackUsesLineSeparatedRomajiList() {
        when(aniListClient.getCurrentSeasonAnime(1)).thenReturn(List.of(
                anime(1, "Grand Blue Season 3", "ぐらんぶる", List.of("Comedy"), 82),
                anime(2, "Mushoku Tensei III", "無職転生", List.of("Fantasy"), 85)
        ));
        when(subscriptionStore.listAll()).thenReturn(List.of());
        when(llmClient.chatWithSystemPrompt(anyString(), anyString(), anyInt())).thenReturn("   ");

        source.check();

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(publisher).publish(eq("ANIME_SEASON"), anyString(), contentCaptor.capture(), anyInt());
        String content = contentCaptor.getValue();

        assertNotNull(content);
        assertTrue(content.contains("Grand Blue Season 3"));
        assertTrue(content.contains("\n"), "回退列表也应逐条换行，实际=" + content);
    }
}
