package org.jarvis.voicegateway.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers TtsService branches not exercised by TtsServiceTest / TtsServiceAdvancedTest /
 * TtsServiceGapsTest:
 * <ul>
 *   <li>{@code synthesizeWithPiper()}'s IOException branch — a "piper-available" selection whose
 *       daemon URL points at a closed loopback port, so {@code HttpClient.send} raises a real
 *       {@link java.io.IOException} (ConnectException) and the wrapper re-throws
 *       "Piper TTS synthesis failed: IO error".</li>
 *   <li>{@code normalizeProvider()}'s null/blank default that folds an unset/blank provider back
 *       to eSpeak before {@code resolveProviderSelection()} switches on it.</li>
 * </ul>
 * All I/O is fully local (an unreachable loopback port); no network, binaries or credentials.
 */
class TtsServiceIoAndProviderCoverageTest {

    @Test
    void synthesizeWithPiperWrapsConnectionFailureAsIoError() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", "piper");
        ReflectionTestUtils.setField(service, "piperAvailable", true);
        // Nothing listens on this loopback port, so the request fails with a real IOException.
        ReflectionTestUtils.setField(service, "piperUrl", "http://127.0.0.1:1");
        ReflectionTestUtils.setField(service, "piperTimeoutMs", 1000L);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.synthesizeDetailed("Привет", "ru-RU", "voice", 1.0, 0.0));

        assertTrue(ex.getMessage().contains("IO error"),
                "expected the IOException branch to wrap the failure as an IO error, got: " + ex.getMessage());
    }

    @Test
    void normalizeProviderDefaultsToEspeakWhenProviderIsNull() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", null);
        ReflectionTestUtils.setField(service, "espeakAvailable", false);

        Map<String, Object> runtime = service.describeRuntime();

        assertEquals("espeak", runtime.get("configuredProvider"));
        assertEquals("unavailable", runtime.get("status"));
        assertFalse((Boolean) runtime.get("available"));
    }

    @Test
    void normalizeProviderDefaultsToEspeakWhenProviderIsBlank() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", "   ");
        ReflectionTestUtils.setField(service, "espeakAvailable", false);

        Map<String, Object> runtime = service.describeRuntime();

        assertEquals("espeak", runtime.get("configuredProvider"));
        assertEquals("unavailable", runtime.get("status"));
    }

    @Test
    void describeRuntimeReportsPiperEffectiveProviderWhenPiperAvailable() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", "piper");
        ReflectionTestUtils.setField(service, "piperAvailable", true);
        ReflectionTestUtils.setField(service, "espeakAvailable", false);

        Map<String, Object> runtime = service.describeRuntime();

        assertEquals("piper", runtime.get("configuredProvider"));
        assertEquals("piper", runtime.get("effectiveProvider"));
        assertEquals("available", runtime.get("status"));
        assertTrue((Boolean) runtime.get("available"));
    }
}
