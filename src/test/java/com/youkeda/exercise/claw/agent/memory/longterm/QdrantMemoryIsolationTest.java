package com.youkeda.exercise.claw.agent.memory.longterm;

import com.google.common.util.concurrent.Futures;
import com.youkeda.exercise.claw.identity.UserExecutionContext;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QdrantMemoryIsolationTest {
    final UserExecutionContext context = new UserExecutionContext();
    final QdrantProperties properties = new QdrantProperties();
    final QdrantMemoryStore store = new QdrantMemoryStore(properties, context);
    final QdrantClient client = mock(QdrantClient.class);

    void assertTenant(Points.Filter filter) {
        assertTrue(filter.getMustList().stream().anyMatch(condition -> condition.hasField()
                && condition.getField().getKey().equals("userId")
                && condition.getField().getMatch().getKeyword().equals("alice")));
    }

    @Test void recallFiltersTenantAndPausedItemsWhileConsolidationIncludesPaused() {
        ReflectionTestUtils.setField(store, "client", client);
        when(client.searchAsync(any(Points.SearchPoints.class))).thenReturn(Futures.immediateFuture(List.of()));
        try (var ignored = context.open("alice")) {
            store.searchScored(new float[]{1, 0}, 5, .45f);
            store.findConsolidationCandidates(new float[]{1, 0}, .9f);
        }
        var requests = ArgumentCaptor.forClass(Points.SearchPoints.class);
        verify(client, times(2)).searchAsync(requests.capture());
        requests.getAllValues().forEach(request -> assertTenant(request.getFilter()));
        assertEquals("disabled", requests.getAllValues().get(0).getFilter().getMustNot(0).getField().getKey());
        assertEquals(0, requests.getAllValues().get(1).getFilter().getMustNotCount());
    }

    @Test void listPaginatesAndEveryPageIsTenantScoped() {
        ReflectionTestUtils.setField(store, "client", client);
        var offset = Points.PointId.newBuilder().setUuid("da825740-b346-4f39-8786-314f5742e211").build();
        when(client.scrollAsync(any(Points.ScrollPoints.class))).thenReturn(
                Futures.immediateFuture(Points.ScrollResponse.newBuilder().setNextPageOffset(offset).build()),
                Futures.immediateFuture(Points.ScrollResponse.newBuilder().build()));
        try (var ignored = context.open("alice")) { assertTrue(store.getAll().isEmpty()); }
        var requests = ArgumentCaptor.forClass(Points.ScrollPoints.class);
        verify(client, times(2)).scrollAsync(requests.capture());
        requests.getAllValues().forEach(request -> assertTenant(request.getFilter()));
        assertEquals(offset, requests.getAllValues().get(1).getOffset());
    }

    @Test void mutationsAndTopicsRequireTenantAndPayloadPersistsPauseAndSource() {
        try (var ignored = context.open("alice")) {
            assertTenant(ReflectionTestUtils.invokeMethod(store, "buildIdFilter", "da825740-b346-4f39-8786-314f5742e211"));
            assertTenant(ReflectionTestUtils.invokeMethod(store, "buildTopicFilter", "diet.spicy"));
            MemoryItem item = MemoryItem.ofManual("喜欢清淡").withDetails(true, "chat-1");
            Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload = ReflectionTestUtils.invokeMethod(store, "buildPayload", item);
            assertEquals("alice", payload.get("userId").getStringValue());
            MemoryItem restored = ReflectionTestUtils.invokeMethod(store, "payloadToMemoryItem", item.id(), payload);
            assertTrue(restored.disabled());
            assertEquals("chat-1", restored.sourceConversationId());
        }
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(store, "tenantFilter"));
    }

    @Test void unavailableStoreDoesNotPretendUserHasNoMemories() {
        assertThrows(IllegalStateException.class, store::getAll);
    }
}
