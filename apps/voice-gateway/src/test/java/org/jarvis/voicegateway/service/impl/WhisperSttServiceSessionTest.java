package org.jarvis.voicegateway.service.impl;

import org.jarvis.voicegateway.exception.SttUnavailableException;
import org.jarvis.voicegateway.service.StreamingRecognitionSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the {@code WhisperSttService.WhisperSession} streaming inner class
 * directly. The session's VAD/buffer bookkeeping runs entirely in Java and does
 * not touch the native whisper.cpp library, so it is reachable even in a sandbox
 * where the model is absent. Only {@code getResult()} on a non-empty buffer
 * would cross the native boundary, and that path fails fast via
 * {@code ensureAvailable()} — asserted here as an {@link SttUnavailableException}.
 */
class WhisperSttServiceSessionTest {

    private WhisperSttService service;

    @BeforeEach
    void setUp() {
        service = new WhisperSttService();
        ReflectionTestUtils.setField(service, "modelPath", "/nonexistent/models/whisper/ggml-small.bin");
    }

    private StreamingRecognitionSession newSession() throws Exception {
        Class<?> sessionClass = Class.forName(
                "org.jarvis.voicegateway.service.impl.WhisperSttService$WhisperSession");
        Constructor<?> ctor = sessionClass.getDeclaredConstructor(WhisperSttService.class, String.class);
        ctor.setAccessible(true);
        return (StreamingRecognitionSession) ctor.newInstance(service, "ru");
    }

    /** 16-bit little-endian PCM whose samples are loud enough to exceed the VAD energy threshold. */
    private byte[] loudPcm(int sampleCount) {
        byte[] data = new byte[sampleCount * 2];
        for (int i = 0; i < sampleCount; i++) {
            // value 10000 = 0x2710 -> low byte first (little-endian)
            data[2 * i] = (byte) 0x10;
            data[2 * i + 1] = (byte) 0x27;
        }
        return data;
    }

    @Test
    void loudFrameMarksSpeakingAndReturnsFalse() throws Exception {
        StreamingRecognitionSession session = newSession();

        boolean finalResult = session.acceptWaveForm(loudPcm(16), 32);

        assertFalse(finalResult, "a loud frame should not trigger a final result");
        assertTrue((Boolean) ReflectionTestUtils.getField(session, "isSpeaking"));
    }

    @Test
    void silenceAfterSpeechBeyondHangoverTriggersFinalResult() throws Exception {
        StreamingRecognitionSession session = newSession();
        // Simulate prior speech that ended more than the 500ms silence window ago.
        ReflectionTestUtils.setField(session, "isSpeaking", true);
        ReflectionTestUtils.setField(session, "lastSpeechTime", System.currentTimeMillis() - 5_000);

        boolean finalResult = session.acceptWaveForm(new byte[32], 32); // all-zero -> silence

        assertTrue(finalResult, "prolonged silence after speech should trigger a final result");
        assertFalse((Boolean) ReflectionTestUtils.getField(session, "isSpeaking"));
    }

    @Test
    void silenceWithoutPriorSpeechReturnsFalse() throws Exception {
        StreamingRecognitionSession session = newSession();

        boolean finalResult = session.acceptWaveForm(new byte[32], 32);

        assertFalse(finalResult);
    }

    @Test
    void partialResultIsAlwaysEmpty() throws Exception {
        StreamingRecognitionSession session = newSession();

        assertEquals("", session.getPartialResult());
    }

    @Test
    void getResultOnEmptyBufferReturnsEmptyStringWithoutTranscribing() throws Exception {
        StreamingRecognitionSession session = newSession();

        assertEquals("", session.getResult());
    }

    @Test
    void getResultWithBufferedAudioFailsFastWhenNativeUnavailable() throws Exception {
        StreamingRecognitionSession session = newSession();
        session.acceptWaveForm(loudPcm(16), 32);

        // Non-empty buffer forces transcribe(), which throws because the service was never init()'d.
        assertThrows(SttUnavailableException.class, session::getResult);
    }

    @Test
    void closeIsIdempotentAndDoesNotThrow() throws Exception {
        StreamingRecognitionSession session = newSession();

        assertNotNull(session);
        session.close();
        session.close();
    }
}
