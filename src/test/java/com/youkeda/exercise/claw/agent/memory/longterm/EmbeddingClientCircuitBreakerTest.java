package com.youkeda.exercise.claw.agent.memory.longterm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingClientCircuitBreakerTest {

    @Test
    void restoresBatchOrderUsingResponseIndices() throws Exception {
        EmbeddingProperties props = new EmbeddingProperties();
        props.setBaseUrl("http://embedding");
        props.setDimension(1);
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":["
                + "{\"index\":1,\"embedding\":[2.0]},"
                + "{\"index\":0,\"embedding\":[1.0]}]}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        EmbeddingClient client = new EmbeddingClient(
                props, new ObjectMapper(), httpClient, httpClient,
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneId.of("UTC")));

        java.util.List<float[]> vectors = client.embedBatch(java.util.List.of("a", "b"));

        assertArrayEquals(new float[]{1f}, vectors.get(0));
        assertArrayEquals(new float[]{2f}, vectors.get(1));
    }

    @Test
    void opensThenHalfOpensAndRecovers() throws Exception {
        EmbeddingProperties props = new EmbeddingProperties();
        props.setBaseUrl("http://embedding");
        props.setDimension(2);
        props.getCircuit().setFailureThreshold(2);
        props.getCircuit().setOpenDuration(Duration.ofSeconds(10));
        MutableClock clock = new MutableClock(Instant.parse("2026-08-03T00:00:00Z"));
        HttpClient http = mock(HttpClient.class);
        HttpClient health = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        when(response.statusCode()).thenReturn(500);
        when(response.body()).thenReturn(
                "{\"data\":[{\"index\":0,\"embedding\":[1.0,2.0]}]}");
        EmbeddingClient client = new EmbeddingClient(
                props, new ObjectMapper(), http, health, clock);

        assertThrows(IllegalStateException.class, () -> client.embed("first"));
        assertThrows(IllegalStateException.class, () -> client.embed("second"));
        assertEquals(EmbeddingClient.CircuitState.OPEN, client.circuitState());
        assertThrows(IllegalStateException.class, () -> client.embed("blocked"));
        verify(http, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        clock.advance(Duration.ofSeconds(11));
        when(response.statusCode()).thenReturn(200);
        assertArrayEquals(new float[]{1f, 2f}, client.embed("probe"));
        assertEquals(EmbeddingClient.CircuitState.CLOSED, client.circuitState());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
