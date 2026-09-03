package com.youkeda.exercise.claw.feature.scout.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.feature.scout.ScoutProperties;
import com.youkeda.exercise.claw.feature.scout.planner.SearchTask;
import com.youkeda.exercise.claw.feature.scout.processor.InformationItem;
import com.youkeda.exercise.claw.feature.websearch.SearchService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSearchCollectorFreshnessTest {

    @Test
    void keepsOnlyResultsWithRecentPublicationDate() {
        SearchService searchService = mock(SearchService.class);
        ScoutProperties properties = new ScoutProperties();
        properties.setFreshnessDays(14);
        String recent = LocalDate.now().minusDays(2).toString();
        String old = LocalDate.now().minusDays(60).toString();
        when(searchService.searchByDate(anyString(), anyInt(), anyInt())).thenReturn("""
                {"results":[
                  {"title":"近期信息","url":"https://example.com/new","content":"新","published_date":"%s"},
                  {"title":"过期信息","url":"https://example.com/old","content":"旧","published_date":"%s"},
                  {"title":"日期未知","url":"https://example.com/unknown","content":"未知"}
                ]}
                """.formatted(recent, old));
        WebSearchCollector collector = new WebSearchCollector(
                searchService, properties, new ObjectMapper());

        List<InformationItem> items = collector.collect(SearchTask.of(
                "latest", SearchTask.NEWS, "test", 5));

        assertEquals(1, items.size());
        assertEquals("近期信息", items.get(0).getTitle());
        verify(searchService).searchByDate("latest", properties.getMaxResultsPerTask(), 14);
    }
}
