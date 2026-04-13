package org.jarvis.voicegateway.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.voicegateway.health.VoiceReadinessService;
import org.jarvis.voicegateway.rules.RuleBasedVoiceCommandService;
import org.jarvis.voicegateway.service.intent.ConfiguredIntentHandler;
import org.jarvis.voicegateway.voice.VoiceAssetLoader;
import org.jarvis.voicegateway.voice.WavResponseRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VoiceRuntimeStatusService {

    private final SttService sttService;
    private final TtsService ttsService;
    private final VoiceAssetLoader voiceAssetLoader;
    private final WavResponseRegistry wavResponseRegistry;
    private final RuleBasedVoiceCommandService ruleBasedVoiceCommandService;
    private final ConfiguredIntentHandler configuredIntentHandler;
    private final VoiceReadinessService voiceReadinessService;

    @Value("${jarvis.voice.pre-recorded.enabled:true}")
    private boolean preRecordedEnabled;

    @Value("${jarvis.stt.provider:vosk}")
    private String configuredSttProvider;

    @Value("${tts.provider:espeak}")
    private String configuredTtsProvider;

    public Map<String, Object> describe() {
        Map<String, Object> stt = new LinkedHashMap<>(sttService.describeRuntime());
        Map<String, Object> tts = new LinkedHashMap<>(ttsService.describeRuntime());
        VoiceReadinessService.Snapshot readinessSnapshot = voiceReadinessService.currentSnapshot();

        boolean sttAvailable = Boolean.TRUE.equals(stt.get("available"));
        boolean ttsAvailable = Boolean.TRUE.equals(tts.get("available"));

        Map<String, Object> preRecorded = new LinkedHashMap<>();
        preRecorded.put("enabled", preRecordedEnabled);
        preRecorded.put("activeAssets", voiceAssetLoader.getActiveAssetCount());
        preRecorded.put("responseProfiles", wavResponseRegistry.getLoadedProfileCount());
        preRecorded.put("status", preRecordedEnabled ? "available" : "disabled");

        Map<String, Object> maturity = new LinkedHashMap<>();
        maturity.put("textCommandPath", "verified");
        maturity.put("websocketTransport", "verified");
        maturity.put("httpSttUploadPath", sttAvailable ? "provider-dependent" : "unavailable");
        maturity.put("websocketAudioPath", sttAvailable ? "provider-dependent" : "unavailable");
        maturity.put("ttsAudioPath", ttsAvailable ? "provider-dependent" : "unavailable");
        maturity.put("preRecordedAssets", preRecordedEnabled ? "provider-independent" : "disabled");
        maturity.put("voiceNotifications", "verified-text");
        maturity.put("wakeWord", "optional");

        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("publicHttpBasePath", "/api/v1/voice");
        routing.put("publicWebSocketPath", "/ws/voice");
        routing.put("internalBasePath", "/internal/voice");
        routing.put("orchestratorBoundary", "/api/v1/orchestrator/execute");
        routing.put("notificationPath", "/internal/voice/notify");
        routing.put("ruleCommands", ruleBasedVoiceCommandService.getLoadedCommandCount());
        routing.put("configuredIntents", configuredIntentHandler.getLoadedCommandsCount());

        Map<String, Object> localDefaultStack = new LinkedHashMap<>();
        localDefaultStack.put("id", "vosk+espeak-ng");
        localDefaultStack.put("sttProvider", "vosk");
        localDefaultStack.put("ttsProvider", "espeak");
        localDefaultStack.put("configuredSttProvider", configuredSttProvider);
        localDefaultStack.put("configuredTtsProvider", configuredTtsProvider);
        localDefaultStack.put("fullAudioReady", sttAvailable && ttsAvailable);
        localDefaultStack.put("status", sttAvailable && ttsAvailable ? "ready" : "partial");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("service", "voice-gateway");
        summary.put("status", sttAvailable && ttsAvailable ? "ready" : "partial");
        summary.put("readiness", Map.of(
                "status", readinessSnapshot.status(),
                "components", readinessSnapshot.components(),
                "componentDetails", readinessSnapshot.componentDetails(),
                "apiGatewayRoute", readinessSnapshot.apiGatewayRoute()));
        summary.put("localDefaultStack", localDefaultStack);
        summary.put("routing", routing);
        summary.put("maturity", maturity);
        summary.put("stt", stt);
        summary.put("tts", tts);
        summary.put("preRecorded", preRecorded);
        return summary;
    }

    public Map<String, Object> describeDiagnostics() {
        VoiceReadinessService.Snapshot readinessSnapshot = voiceReadinessService.currentSnapshot();
        Map<String, Object> stt = new LinkedHashMap<>(sttService.describeRuntime());
        Map<String, Object> tts = new LinkedHashMap<>(ttsService.describeRuntime());

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("service", "voice-gateway");
        diagnostics.put("status", readinessSnapshot.status());
        diagnostics.put("capture", captureDiagnostics());
        diagnostics.put("execution", executionDiagnostics());
        diagnostics.put("stt", providerDiagnostics(stt, readinessSnapshot.componentDetails().get("stt")));
        diagnostics.put("tts", providerDiagnostics(tts, readinessSnapshot.componentDetails().get("tts")));
        diagnostics.put("assets", componentDiagnostics(readinessSnapshot.componentDetails().get("assets")));
        diagnostics.put("preRecorded", preRecordedDiagnostics());
        diagnostics.put("orchestrator", componentDiagnostics(readinessSnapshot.componentDetails().get("orchestrator")));
        diagnostics.put("websocket", componentDiagnostics(readinessSnapshot.componentDetails().get("websocket")));
        diagnostics.put("apiGatewayRoute", routeDiagnostics(readinessSnapshot.apiGatewayRoute()));
        return diagnostics;
    }

    private Map<String, Object> captureDiagnostics() {
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("managedBy", "desktop-client");
        capture.put("microphoneProbe", "not-applicable");
        capture.put("reason", "voice-gateway receives websocket PCM frames from clients and does not open host capture devices");
        return capture;
    }

    private Map<String, Object> executionDiagnostics() {
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("primaryCommandLoop", "rule-based");
        execution.put("orchestratorRequiredForFullCommandSet", true);
        execution.put("localFallbackEnabled", true);
        execution.put("pcControlRequiresConnectedDesktopClient", true);
        execution.put("pcControlWebSocketPath", "/ws/pc-control");
        execution.put("runtimeCapabilitySource", "/api/v1/capabilities");
        execution.put("readinessScope", java.util.List.of(
                "stt",
                "tts",
                "assets",
                "orchestrator",
                "websocket",
                "apiGatewayRoute"));
        execution.put("notCoveredByReadiness", java.util.List.of(
                "authenticated desktop executor presence",
                "runtime-mode-specific pc-control support",
                "smart-home provider execution availability"));
        return execution;
    }

    private Map<String, Object> preRecordedDiagnostics() {
        Map<String, Object> preRecorded = new LinkedHashMap<>();
        preRecorded.put("enabled", preRecordedEnabled);
        preRecorded.put("status", preRecordedEnabled ? "available" : "disabled");
        preRecorded.put("activeAssetCount", voiceAssetLoader.getActiveAssetCount());
        preRecorded.put("responseProfileCount", wavResponseRegistry.getLoadedProfileCount());
        return preRecorded;
    }

    private Map<String, Object> providerDiagnostics(
            Map<String, Object> runtime,
            VoiceReadinessService.ComponentSnapshot snapshot) {
        Map<String, Object> diagnostics = new LinkedHashMap<>(runtime);
        diagnostics.putAll(componentDiagnostics(snapshot));
        return diagnostics;
    }

    private Map<String, Object> componentDiagnostics(VoiceReadinessService.ComponentSnapshot snapshot) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        if (snapshot == null) {
            diagnostics.put("componentStatus", "UNKNOWN");
            diagnostics.put("working", false);
            return diagnostics;
        }
        diagnostics.put("componentStatus", snapshot.status());
        diagnostics.put("working", "UP".equals(snapshot.status()));
        diagnostics.put("reasonCode", snapshot.reasonCode());
        diagnostics.put("message", snapshot.message());
        diagnostics.put("details", snapshot.details());
        return diagnostics;
    }

    private Map<String, Object> routeDiagnostics(VoiceReadinessService.DownstreamRouteSnapshot snapshot) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        if (snapshot == null) {
            diagnostics.put("status", "UNKNOWN");
            diagnostics.put("working", false);
            return diagnostics;
        }
        diagnostics.put("status", snapshot.status());
        diagnostics.put("working", "UP".equals(snapshot.status()));
        diagnostics.put("reasonCode", snapshot.reasonCode());
        diagnostics.put("message", snapshot.message());
        diagnostics.put("details", snapshot.details());
        return diagnostics;
    }
}
