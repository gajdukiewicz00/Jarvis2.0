package org.jarvis.voicegateway.service;

import org.jarvis.voicegateway.exception.TtsUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TtsServiceTest {

    @Test
    void describeRuntimeReportsUnavailableWhenEspeakIsMissing() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", "espeak");
        ReflectionTestUtils.setField(service, "configuredEspeakBinaryPath", "/home/test/.jarvis/tools/bin/espeak-ng");
        ReflectionTestUtils.setField(service, "espeakAvailable", false);
        ReflectionTestUtils.setField(service, "googleTtsAvailable", false);

        Map<String, Object> runtime = service.describeRuntime();

        assertEquals("espeak", runtime.get("configuredProvider"));
        assertEquals("unavailable", runtime.get("status"));
        assertEquals(false, runtime.get("available"));
        assertEquals("/home/test/.jarvis/tools/bin/espeak-ng", runtime.get("configuredBinaryPath"));
        assertThrows(TtsUnavailableException.class,
                () -> service.synthesize("Привет", "ru-RU", "ru-RU-Wavenet-A", 1.0, 0.0));
    }

    @Test
    void describeRuntimeReportsGoogleFallbackAsDegradedWhenEspeakIsReady() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", "google");
        ReflectionTestUtils.setField(service, "googleTtsAvailable", false);
        ReflectionTestUtils.setField(service, "googleInitStatus", "Google credentials missing");
        ReflectionTestUtils.setField(service, "espeakAvailable", true);
        ReflectionTestUtils.setField(service, "espeakBinary", "espeak-ng");
        ReflectionTestUtils.setField(service, "resolvedEspeakBinaryPath", "/home/test/.jarvis/tools/bin/espeak-ng");

        Map<String, Object> runtime = service.describeRuntime();

        assertEquals("google", runtime.get("configuredProvider"));
        assertEquals("espeak", runtime.get("effectiveProvider"));
        assertEquals("degraded", runtime.get("status"));
        assertEquals(true, runtime.get("available"));
        assertEquals("/home/test/.jarvis/tools/bin/espeak-ng", runtime.get("resolvedBinaryPath"));
    }

    @Test
    void unsupportedProviderFailsHonestly() {
        TtsService service = new TtsService();
        ReflectionTestUtils.setField(service, "ttsEnabled", true);
        ReflectionTestUtils.setField(service, "ttsProvider", "festival");

        Map<String, Object> runtime = service.describeRuntime();

        assertEquals("festival", runtime.get("configuredProvider"));
        assertEquals("unavailable", runtime.get("status"));
        assertThrows(TtsUnavailableException.class,
                () -> service.synthesize("Hello", "en-US", "en-US-Wavenet-D", 1.0, 0.0));
    }
}
