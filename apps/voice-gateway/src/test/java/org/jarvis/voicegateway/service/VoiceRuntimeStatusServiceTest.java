package org.jarvis.voicegateway.service;

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

        VoiceRuntimeStatusService service = new VoiceRuntimeStatusService(
                sttService,
                ttsService,
                voiceAssetLoader,
                wavResponseRegistry,
                ruleBasedVoiceCommandService,
                configuredIntentHandler);
        ReflectionTestUtils.setField(service, "preRecordedEnabled", true);

        Map<String, Object> status = service.describe();

        assertEquals("partial", status.get("status"));
        assertEquals("vosk+espeak-ng", ((Map<?, ?>) status.get("localDefaultStack")).get("id"));
        assertEquals(false, ((Map<?, ?>) status.get("localDefaultStack")).get("fullAudioReady"));
        assertEquals("verified", ((Map<?, ?>) status.get("maturity")).get("textCommandPath"));
        assertEquals("unavailable", ((Map<?, ?>) status.get("maturity")).get("ttsAudioPath"));
        assertEquals(16, ((Map<?, ?>) status.get("preRecorded")).get("activeAssets"));
    }
}
