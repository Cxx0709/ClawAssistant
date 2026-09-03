package com.youkeda.exercise.claw.web.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.artifact.ArtifactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class ChatTranscriptServiceTest {

    private ChatTranscriptService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:", true));
        jdbc.execute("""
            CREATE TABLE chat_messages (
              id TEXT PRIMARY KEY, user_id TEXT, conversation_id TEXT, role TEXT, content TEXT DEFAULT '',
              attachments_json TEXT DEFAULT '[]', artifacts_json TEXT DEFAULT '[]',
              tool_trace_json TEXT DEFAULT '[]', skills_json TEXT DEFAULT '[]', status TEXT,
              run_id TEXT, error_text TEXT, total_ms INTEGER, created_at INTEGER, updated_at INTEGER)
        """);
        jdbc.execute("""
            CREATE TABLE chat_runs (
              id TEXT PRIMARY KEY, user_id TEXT, conversation_id TEXT, user_message_id TEXT,
              assistant_message_id TEXT, status TEXT, draft_content TEXT DEFAULT '',
              tool_trace_json TEXT DEFAULT '[]', skills_json TEXT DEFAULT '[]', artifacts_json TEXT DEFAULT '[]',
              error_text TEXT, started_at INTEGER, updated_at INTEGER, finished_at INTEGER)
        """);
        jdbc.execute("CREATE VIRTUAL TABLE chat_message_search USING fts5(message_id, conversation_id, user_id, content)");
        service = new ChatTranscriptService(jdbc, new ObjectMapper(), mock(ArtifactService.class));
    }

    @Test
    void keepsDraftAndReturnsCompletedRunAfterReconnect() {
        ChatTranscriptService.RunStart run = service.start("u1", "c1", "规划杭州", List.of(), "r1");
        service.checkpoint("u1", "r1", "正在规划", List.of(
                new ToolTraceItem("t1", "search", "travel", "running", null, null)), List.of("travel"));

        TranscriptMessage draft = service.findByRun("u1", "r1");
        assertEquals("STREAMING", draft.status());
        assertEquals("正在规划", draft.content());
        assertEquals(run.assistantMessageId(), draft.id());

        service.complete("u1", "r1", "杭州两日方案", List.of(), List.of("travel"), List.of(), 321L);
        TranscriptMessage completed = service.findByRun("u1", "r1");
        assertEquals("COMPLETED", completed.status());
        assertEquals(321L, completed.totalMs());
        assertNotNull(service.page("u1", "c1", null, 1).nextCursor());
    }
}
