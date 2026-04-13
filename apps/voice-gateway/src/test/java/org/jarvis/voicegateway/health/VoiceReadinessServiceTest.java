package org.jarvis.voicegateway.health;

import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.voicegateway.service.SttService;
import org.jarvis.voicegateway.service.TtsService;
import org.jarvis.voicegateway.voice.VoiceAssetLoader;
import org.jarvis.voicegateway.voice.WavResponseRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class VoiceReadinessServiceTest {

    private VoiceReadinessService service;

    @BeforeEach
    void setUp() {
        service = new VoiceReadinessService(
                mock(SttService.class),
                mock(TtsService.class),
                mock(VoiceAssetLoader.class),
                mock(WavResponseRegistry.class),
                RestClient.builder(),
                mock(ServiceJwtProvider.class));
    }

    @Test
    void marksReadinessDownWhenSttIsDown() {
        assertEquals("DOWN", overallStatus(Map.of(
                "stt", component("DOWN"),
                "tts", component("UP"),
                "assets", component("UP"),
                "orchestrator", component("UP"),
                "websocket", component("UP"))));
    }

    @Test
    void marksReadinessDownWhenWebsocketIsDown() {
        assertEquals("DOWN", overallStatus(Map.of(
                "stt", component("UP"),
                "tts", component("UP"),
                "assets", component("UP"),
                "orchestrator", component("UP"),
                "websocket", component("DOWN"))));
    }

    @Test
    void marksReadinessDegradedWhenOnlyTtsOrchestratorOrAssetsAreDown() {
        assertEquals("DEGRADED", overallStatus(Map.of(
                "stt", component("UP"),
                "tts", component("DOWN"),
                "assets", component("UP"),
                "orchestrator", component("UP"),
                "websocket", component("UP"))));
        assertEquals("DEGRADED", overallStatus(Map.of(
                "stt", component("UP"),
                "tts", component("UP"),
                "assets", component("DOWN"),
                "orchestrator", component("UP"),
                "websocket", component("UP"))));
        assertEquals("DEGRADED", overallStatus(Map.of(
                "stt", component("UP"),
                "tts", component("UP"),
                "assets", component("UP"),
                "orchestrator", component("DOWN"),
                "websocket", component("UP"))));
    }

    @Test
    void marksReadinessUpWhenCoreVoicePathIsHealthy() {
        assertEquals("UP", overallStatus(Map.of(
                "stt", component("UP"),
                "tts", component("UP"),
                "assets", component("UP"),
                "orchestrator", component("UP"),
                "websocket", component("UP"))));
    }

    private String overallStatus(Map<String, VoiceReadinessService.ComponentSnapshot> components) {
        return (String) ReflectionTestUtils.invokeMethod(service, "overallStatus", new LinkedHashMap<>(components));
    }

    private VoiceReadinessService.ComponentSnapshot component(String status) {
        return new VoiceReadinessService.ComponentSnapshot(status, status + "_CODE", status.toLowerCase(), Map.of());
    }
}
