package com.youkeda.exercise.claw.ai.llm;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LLMClientStreamTimeoutTest {

    @Test
    void timesOutWhenStreamingBodyStopsProducingLines() throws Exception {
        try (PipedInputStream input = new PipedInputStream();
             PipedOutputStream ignored = new PipedOutputStream(input);
             LLMClient.StreamLineReader reader = new LLMClient.StreamLineReader(input)) {
            assertThrows(HttpTimeoutException.class,
                    () -> reader.readLine(Duration.ofMillis(100)));
        }
    }

    @Test
    void returnsLinesAndEndOfStreamNormally() throws Exception {
        byte[] body = "data: one\n\ndata: [DONE]\n".getBytes(StandardCharsets.UTF_8);
        try (LLMClient.StreamLineReader reader = new LLMClient.StreamLineReader(
                new ByteArrayInputStream(body))) {
            assertEquals("data: one", reader.readLine(Duration.ofSeconds(1)));
            assertEquals("", reader.readLine(Duration.ofSeconds(1)));
            assertEquals("data: [DONE]", reader.readLine(Duration.ofSeconds(1)));
            assertNull(reader.readLine(Duration.ofSeconds(1)));
        }
    }
}
