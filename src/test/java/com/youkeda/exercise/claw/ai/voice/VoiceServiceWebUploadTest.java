package com.youkeda.exercise.claw.ai.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceServiceWebUploadTest {

    @Test
    void webUploadKeepsCodecBearingFileName() throws Exception {
        VoiceClient client = mock(VoiceClient.class);
        byte[] audio = new byte[] {1, 2, 3};
        when(client.asr(audio, "recording.webm")).thenReturn("识别文本");
        VoiceService service = new VoiceService(client, new VoiceProperties(), new ObjectMapper());

        assertEquals("识别文本", service.transcribe(audio, "recording.webm"));
        verify(client).asr(audio, "recording.webm");
    }
}
