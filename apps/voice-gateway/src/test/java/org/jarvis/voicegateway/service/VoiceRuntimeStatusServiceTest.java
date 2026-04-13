package org.jarvis.voicegateway.service;

import org.jarvis.voicegateway.health.VoiceReadinessService;
import org.jarvis.voicegateway.rules.RuleBasedVoiceCommandService;
import org.jarvis.voicegateway.service.intent.ConfiguredIntentHandler;
import org.jarvis.voicegateway.voice.VoiceAssetLoader;
import org.jarvis.voicegateway.voice.WavResponseRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VoiceRuntimeStatusServiceTest {

    @Test
    void describeMarksRuntimePartialWhenAudioProvidersAreUnavailable() {
        SttService sttService = mock(SttService.class);
        TtsService ttsService = mock(TtsService.class);
        VoiceAssetLoader voiceAssetLoader = mock(VoiceAssetLoader.class);
        WavResponseRegistry wavResponseRegistry = mock(WavResponseRegistry.class);
        RuleBasedVoiceCommandService ruleBasedVoiceCommandService = mock(RuleBasedVoiceCommandService.class);
        ConfiguredIntentHandler configuredIntentHandler = mock(ConfiguredIntentHandler.class);
        VoiceReadinessService voiceReadinessService = mock(VoiceReadinessService.class);

        when(sttService.describeRuntime()).thenReturn(Map.of(
                "configuredProvider", "noop",
                "status", "disabled",
                "available", false));
        when(ttsService.describeRuntime()).thenReturn(Map.of(
                "configuredProvider", "espeak",
                "status", "unavailable",
                "available", false));
        when(voiceAssetLoader.getActiveAssetCount()).thenReturn(16);
        when(wavResponseRegistry.getLoadedProfileCount()).thenReturn(24);
        when(ruleBasedVoiceCommandService.getLoadedCommandCount()).thenReturn(42);
        when(configuredIntentHandler.getLoadedCommandsCount()).thenReturn(15);
        when(voiceReadinessService.currentSnapshot()).thenReturn(new VoiceReadinessService.Snapshot(
                "DEGRADED",
                Map.of(
                        "stt", "DOWN",
                        "tts", "DOWN",
                        "assets", "UP",
                        "orchestrator", "UP",
                        "websocket", "UP"),
                Map.of(),
                new VoiceReadinessService.DownstreamRouteSnapshot("DOWN", "API_GATEWAY_UNREACHABLE", "down", Map.of())));

        VoiceRuntimeStatusService service = new VoiceRuntimeStatusService(
                sttService,
                ttsService,
                voiceAssetLoader,
                wavResponseRegistry,
                ruleBasedVoiceCommandService,
                configuredIntentHandler,
                voiceReadinessService);
        ReflectionTestUtils.setField(service, "preRecordedEnabled", true);

        Map<String, Object> status = service.describe();

        assertEquals("partial", status.get("status"));
        assertEquals("vosk+espeak-ng", ((Map<?, ?>) status.get("localDefaultStack")).get("id"));
        assertEquals(false, ((Map<?, ?>) status.get("localDefaultStack")).get("fullAudioReady"));
        assertEquals("verified", ((Map<?, ?>) status.get("maturity")).get("textCommandPath"));
        assertEquals("unavailable", ((Map<?, ?>) status.get("maturity")).get("ttsAudioPath"));
        assertEquals(16, ((Map<?, ?>) status.get("preRecorded")).get("activeAssets"));
    }

    @Test
    void diagnosticsExposeBackendTruthWithoutPretendingToOwnMicrophones() {
        SttService sttService = mock(SttService.class);
        TtsService ttsService = mock(TtsService.class);
        VoiceAssetLoader voiceAssetLoader = mock(VoiceAssetLoader.class);
        WavResponseRegistry wavResponseRegistry = mock(WavResponseRegistry.class);
        RuleBasedVoiceCommandService ruleBasedVoiceCommandService = mock(RuleBasedVoiceCommandService.class);
        ConfiguredIntentHandler configuredIntentHandler = mock(ConfiguredIntentHandler.class);
        VoiceReadinessService voiceReadinessService = mock(VoiceReadinessService.class);

        when(sttService.describeRuntime()).thenReturn(Map.of(
                "configuredProvider", "vosk",
                "status", "available",
                "available", true));
        when(ttsService.describeRuntime()).thenReturn(Map.of(
                "configuredProvider", "espeak",
                "status", "degraded",
                "available", false,
                "reason", "espeak missing"));
        when(voiceAssetLoader.getActiveAssetCount()).thenReturn(95);
        when(wavResponseRegistry.getLoadedProfileCount()).thenReturn(109);
        when(voiceReadinessService.currentSnapshot()).thenReturn(new VoiceReadinessService.Snapshot(
                "DEGRADED",
                Map.of(
                        "stt", "UP",
                        "tts", "DOWN",
                        "assets", "UP",
                        "orchestrator", "UP",
                        "websocket", "UP"),
                Map.of(
                        "stt", new VoiceReadinessService.ComponentSnapshot("UP", "STT_READY", "stt ok", Map.of("sampleRate", 16000)),
                        "tts", new VoiceReadinessService.ComponentSnapshot("DOWN", "TTS_PROVIDER_UNAVAILABLE", "tts unavailable", Map.of("configuredProvider", "espeak")),
                        "assets", new VoiceReadinessService.ComponentSnapshot("UP", "VOICE_ASSETS_READY", "assets ok", Map.of("verifiedAssets", 95)),
                        "orchestrator", new VoiceReadinessService.ComponentSnapshot("UP", "ORCHESTRATOR_READY", "orchestrator ok", Map.of("orchestratorUrl", "http://orchestrator")),
                        "websocket", new VoiceReadinessService.ComponentSnapshot("UP", "WEBSOCKET_READY", "websocket ok", Map.of("url", "ws://127.0.0.1:8081/ws/voice"))),
                new VoiceReadinessService.DownstreamRouteSnapshot("UP", "API_GATEWAY_ROUTE_READY", "gateway ok", Map.of("apiGatewayUrl", "http://api-gateway"))));

        VoiceRuntimeStatusService service = new VoiceRuntimeStatusService(
                sttService,
                ttsService,
                voiceAssetLoader,
                wavResponseRegistry,
                ruleBasedVoiceCommandService,
                configuredIntentHandler,
                voiceReadinessService);
        ReflectionTestUtils.setField(service, "preRecordedEnabled", true);

        Map<String, Object> diagnostics = service.describeDiagnostics();

        assertEquals("voice-gateway", diagnostics.get("service"));
        assertEquals("DEGRADED", diagnostics.get("status"));
        assertEquals("desktop-client", ((Map<?, ?>) diagnostics.get("capture")).get("managedBy"));
        assertEquals("not-applicable", ((Map<?, ?>) diagnostics.get("capture")).get("microphoneProbe"));
        assertEquals("rule-based", ((Map<?, ?>) diagnostics.get("execution")).get("primaryCommandLoop"));
        assertEquals(true, ((Map<?, ?>) diagnostics.get("execution")).get("orchestratorRequiredForFullCommandSet"));
        assertEquals("/api/v1/capabilities", ((Map<?, ?>) diagnostics.get("execution")).get("runtimeCapabilitySource"));
        assertEquals(true, ((Map<?, ?>) diagnostics.get("stt")).get("working"));
        assertEquals(false, ((Map<?, ?>) diagnostics.get("tts")).get("working"));
        assertEquals(95, ((Map<?, ?>) diagnostics.get("preRecorded")).get("activeAssetCount"));
        assertEquals("UP", ((Map<?, ?>) diagnostics.get("apiGatewayRoute")).get("status"));
    }
}
