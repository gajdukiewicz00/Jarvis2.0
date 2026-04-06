package org.jarvis.voicegateway.service;

import lombok.RequiredArgsConstructor;
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

    @Value("${jarvis.voice.pre-recorded.enabled:true}")
    private boolean preRecordedEnabled;

    @Value("${jarvis.stt.provider:vosk}")
    private String configuredSttProvider;

    @Value("${tts.provider:espeak}")
    private String configuredTtsProvider;

    public Map<String, Object> describe() {
        Map<String, Object> stt = new LinkedHashMap<>(sttService.describeRuntime());
        Map<String, Object> tts = new LinkedHashMap<>(ttsService.describeRuntime());

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
        summary.put("localDefaultStack", localDefaultStack);
        summary.put("routing", routing);
        summary.put("maturity", maturity);
        summary.put("stt", stt);
        summary.put("tts", tts);
        summary.put("preRecorded", preRecorded);
        return summary;
    }
}
