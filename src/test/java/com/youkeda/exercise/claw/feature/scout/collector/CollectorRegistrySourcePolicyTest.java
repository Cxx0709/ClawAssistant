package com.youkeda.exercise.claw.feature.scout.collector;

import com.youkeda.exercise.claw.feature.scout.planner.SearchTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class CollectorRegistrySourcePolicyTest {

    @Test
    void routesByCategoryCollectsRssOnceAndExcludesTavilyJobCollectors() {
        Collector web = collector("WEB_SEARCH");
        Collector rss = collector("RSS");
        Collector github = collector("GITHUB");
        Collector competition = collector("COMPETITION");
        Collector job = collector("JOB");
        CollectorRegistry registry = new CollectorRegistry(
                List.of(web, rss, github, competition, job));

        SearchTask news = SearchTask.of(
                "AI framework latest", SearchTask.NEWS, "latest tech", 5);
        SearchTask project = SearchTask.of(
                "AI repository latest", SearchTask.GITHUB, "latest code", 4);
        registry.collectAll(List.of(news, project));

        verify(web).collect(news);
        verify(github).collect(project);
        verify(rss, times(1)).collect(any());
        verify(web, times(1)).collect(any());
        verify(github, times(1)).collect(any());
        verify(competition, never()).collect(any());
        verify(job, never()).collect(any());
    }

    @Test
    void fallsBackToWebSearchWhenGithubCollectorIsUnavailable() {
        Collector web = collector("WEB_SEARCH");
        CollectorRegistry registry = new CollectorRegistry(List.of(web));
        SearchTask project = SearchTask.of(
                "AI repository latest", SearchTask.GITHUB, "latest code", 4);

        registry.collectAll(List.of(project));

        verify(web).collect(project);
    }

    @Test
    void doesNotSendJobOrCompetitionTasksToGenericTavilyCollector() {
        Collector web = collector("WEB_SEARCH");
        CollectorRegistry registry = new CollectorRegistry(List.of(web));

        registry.collectAll(List.of(
                SearchTask.of("AI jobs latest", SearchTask.JOB, "jobs", 3),
                SearchTask.of("AI competitions latest", SearchTask.COMPETITION, "contests", 3)));

        verify(web, never()).collect(any());
    }

    private Collector collector(String type) {
        Collector collector = mock(Collector.class);
        when(collector.getType()).thenReturn(type);
        when(collector.collect(any())).thenReturn(List.of());
        return collector;
    }
}
