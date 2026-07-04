package org.jarvis.voicegateway.service.impl;

import org.jarvis.voicegateway.exception.SttUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WhisperSttServiceTest deliberately never calls {@code init()}: that method
 * loads the native whisper.cpp JNI library, which is not guaranteed to be
 * present/loadable in the test sandbox. Instead these tests exercise the
 * service in its default (uninitialized) state, which is exactly the state
 * production code sees whenever the whisper model/library isn't available —
 * a real, reachable branch, not a synthetic shortcut.
 */
class WhisperSttServiceTest {

    private WhisperSttService service;

    @BeforeEach
    void setUp() {
        service = new WhisperSttService();
        ReflectionTestUtils.setField(service, "modelPath", "/nonexistent/models/whisper/ggml-small.bin");
    }

    @Test
    void providerIdIsWhisper() {
        assertEquals("whisper", service.providerId());
    }

    @Test
    void describeRuntimeReportsUnavailableWhenNotInitialized() {
        Map<String, Object> runtime = service.describeRuntime();

        assertEquals("whisper", runtime.get("configuredProvider"));
        assertEquals("unavailable", runtime.get("status"));
        assertFalse((Boolean) runtime.get("available"));
        assertFalse((Boolean) runtime.get("pathExists"));
        assertEquals("Whisper STT not initialized", runtime.get("reason"));
    }

    @Test
    void transcribeThrowsSttUnavailableExceptionWhenNotInitialized() {
        assertThrows(SttUnavailableException.class, () -> service.transcribe(new byte[3200], "ru-RU"));
    }

    @Test
    void createSessionThrowsWhenNotInitialized() {
        assertThrows(SttUnavailableException.class, () -> service.createSession("en-US"));
    }

    @Test
    void createSessionWithNoArgsThrowsWhenNotInitialized() {
        assertThrows(SttUnavailableException.class, () -> service.createSession());
    }

    @Test
    void bytesToFloatsConvertsLittleEndian16BitPcm() {
        // 0x0000 -> 0.0f, 0x7FFF -> ~1.0f (max positive), 0x8000 (as unsigned) -> -1.0f
        byte[] pcm = new byte[] {
                (byte) 0x00, (byte) 0x00, // sample 0 = 0
                (byte) 0xFF, (byte) 0x7F, // sample 1 = 32767
                (byte) 0x00, (byte) 0x80, // sample 2 = -32768
        };

        float[] floats = (float[]) ReflectionTestUtils.invokeMethod(service, "bytesToFloats", (Object) pcm);

        assertEquals(3, floats.length);
        assertEquals(0.0f, floats[0], 0.0001f);
        assertEquals(32767 / 32768.0f, floats[1], 0.0001f);
        assertEquals(-1.0f, floats[2], 0.0001f);
    }
}
