package com.youkeda.exercise.claw.agent.memory.longterm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.claw.identity.*;
import com.youkeda.exercise.claw.web.MemoryController;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryManagementTest {
    @TempDir Path directory;
    final UserExecutionContext context = new UserExecutionContext();
    final MemoryStore store = mock(MemoryStore.class);
    final EmbeddingClient embeddings = mock(EmbeddingClient.class);
    final MemoryTopicResolver topics = mock(MemoryTopicResolver.class);
    final MemoryExtractor extractor = mock(MemoryExtractor.class);
    final Map<String, Map<String, MemoryItem>> tenants = new HashMap<>();
    MemoryChangeStore changes;
    LongTermMemoryService service;
    UserExecutionContext.Scope scope;

    @BeforeEach void setup() {
        scope = context.open("alice", "chat-1");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("memory.db")));
        changes = new MemoryChangeStore(jdbc, new ObjectMapper().findAndRegisterModules(), context);
        changes.init();
        when(store.getAll()).thenAnswer(invocation -> List.copyOf(current().values()));
        when(store.upsert(any(), any())).thenAnswer(invocation -> {
            MemoryItem item = invocation.getArgument(0);
            current().put(item.id(), item);
            return true;
        });
        when(store.delete(anyString())).thenAnswer(invocation -> current().remove(invocation.getArgument(0)) != null);
        when(embeddings.embed(anyString())).thenReturn(new float[]{1, 0});
        when(topics.resolve(any(), anyString())).thenReturn(new MemoryTopicResolver.TopicResolution("learning.style", 1));
        service = new LongTermMemoryService(new LongTermMemoryProperties(), extractor, embeddings, store, topics,
                mock(MemoryConsolidator.class), new MemoryWriteCoordinator(), mock(MemoryEvictionService.class),
                Runnable::run, context, changes);
    }

    @AfterEach void cleanup() { scope.close(); }

    Map<String, MemoryItem> current() {
        return tenants.computeIfAbsent(context.requireUserId(), key -> new HashMap<>());
    }

    @Test void editReembedsAndKeepsIdentityThenPauseExcludesRecall() {
        MemoryItem original = service.createManaged(MemoryCategory.PREFERENCE, "解释时多举例");
        MemoryItem updated = service.updateManaged(original.id(), MemoryCategory.RULE, "请用简短的例子", false, original.updatedAt());
        assertEquals(original.id(), updated.id());
        assertEquals(original.createdAt(), updated.createdAt());
        assertTrue(updated.updatedAt().isAfter(original.updatedAt()));
        verify(embeddings).embed("请用简短的例子");
        MemoryItem paused = service.updateManaged(updated.id(), updated.category(), updated.content(), true, updated.updatedAt());
        when(store.searchScored(any(), anyInt(), anyFloat())).thenReturn(List.of(new MemorySearchResult(paused, 1f)));
        assertTrue(service.recall("如何学习").isEmpty());
        assertEquals(1, service.listAll().size());
        assertTrue(service.listAll().get(0).disabled());
    }

    @Test void staleUpdateAndUndoCannotOverwriteNewerChanges() {
        MemoryItem original = service.createManaged(MemoryCategory.PREFERENCE, "原来的偏好");
        long firstChange = changes.recent("chat-1").get(0).id();
        MemoryItem updated = service.updateManaged(original.id(), original.category(), "新的偏好", false, original.updatedAt());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.updateManaged(original.id(), original.category(), "过期修改", false, original.updatedAt())).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.undoManaged(firstChange)).getStatusCode());
        assertEquals(updated.content(), current().get(original.id()).content());
    }

    @Test void undoRestoresPreviousContentAndDeletionRemovesSnapshots() {
        MemoryItem original = service.createManaged(MemoryCategory.PREFERENCE, "旧内容");
        service.updateManaged(original.id(), original.category(), "新内容", false, original.updatedAt());
        long changeId = changes.recent("chat-1").get(0).id();
        service.undoManaged(changeId);
        MemoryItem restored = current().get(original.id());
        assertEquals("旧内容", restored.content());
        assertNull(changes.find(changeId));
        service.deleteManaged(restored.id(), restored.updatedAt());
        assertTrue(current().isEmpty());
        assertTrue(changes.recent("chat-1").isEmpty());
    }

    @Test void undoNewMemoryDeletesIt() {
        service.createManaged(MemoryCategory.FACT, "用户主动提供的信息");
        service.undoManaged(changes.recent("chat-1").get(0).id());
        assertTrue(current().isEmpty());
    }

    @Test void authenticatedControllerCannotAccessAnotherTenantsMemoryOrReceipt() {
        MemoryItem alice = service.createManaged(MemoryCategory.FACT, "Alice 的信息");
        long changeId = changes.recent("chat-1").get(0).id();
        AuthenticatedUser users = mock(AuthenticatedUser.class);
        Authentication authentication = mock(Authentication.class);
        when(users.require(authentication)).thenReturn(new AppUser("bob", "bob", "", "Bob", true, Instant.now()));
        MemoryController controller = new MemoryController(service, changes, users, context);
        assertEquals(List.of(), controller.list(authentication).get("items"));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> controller.update(authentication,
                alice.id(), new MemoryController.Edit(MemoryCategory.FACT, "篡改", false, alice.updatedAt()))).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> controller.delete(authentication, alice.id(), alice.updatedAt())).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> controller.undo(authentication, changeId)).getStatusCode());
        assertTrue(controller.changes(authentication, "chat-1").isEmpty());
        assertEquals("alice", context.requireUserId());
        assertEquals(alice, current().get(alice.id()));
    }

    @Test void failedEmbeddingLeavesExistingMemoryIntact() {
        MemoryItem original = service.createManaged(MemoryCategory.FACT, "已有信息");
        when(embeddings.embed("新的信息")).thenThrow(new IllegalStateException("offline"));
        assertThrows(IllegalStateException.class, () -> service.updateManaged(original.id(), original.category(),
                "新的信息", false, original.updatedAt()));
        assertEquals(original, current().get(original.id()));
    }

    @Test void datesRemainIsoStringsWithTheApplicationsJacksonConfiguration() throws Exception {
        var mapper = new com.youkeda.exercise.claw.infrastructure.common.JacksonConfig().objectMapper();
        MemoryItem item = MemoryItem.ofManual("测试日期格式");
        var json = mapper.readTree(mapper.writeValueAsString(item));
        assertTrue(json.get("updatedAt").isTextual());
        assertEquals(item.updatedAt(), Instant.parse(json.get("updatedAt").asText()));
        assertEquals(item, mapper.treeToValue(json, MemoryItem.class));
    }

    @Test void extractedMemoryRecordsConversationAndPausedSemanticMatchIsNotReactivated() {
        MemoryItem incoming = MemoryItem.ofAuto(MemoryCategory.PREFERENCE, "", "喜欢安静的旅行地点", "我想去人少的地方", 1, 1);
        when(extractor.extract(anyString(), anyString())).thenReturn(List.of(incoming));
        service.processAndStore("下次旅行想去人比较少的地方", "好的");
        assertEquals("chat-1", current().get(incoming.id()).sourceConversationId());
        MemoryItem paused = service.updateManaged(incoming.id(), incoming.category(), incoming.content(), true, incoming.updatedAt());
        when(store.findConsolidationCandidates(any(), anyFloat())).thenReturn(List.of(paused));
        clearInvocations(store);
        service.processAndStore("下次旅行还是想去人比较少的地方", "好的");
        verify(store, never()).upsert(any(), any());
        assertTrue(current().get(incoming.id()).disabled());
    }
}
