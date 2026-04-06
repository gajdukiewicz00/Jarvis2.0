package org.jarvis.voicegateway.voice;

import org.jarvis.voicegateway.service.TtsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceOutputServiceTest {

    @Mock
    private TtsService ttsService;
    @Mock
    private RuleBasedVoiceOutputResolver resolver;
    @Mock
    private VoiceAssetLoader assetLoader;
    @Mock
    private WavResponseRegistry wavResponseRegistry;

    private VoiceOutputService service;

    @BeforeEach
    void setUp() {
        service = new VoiceOutputService(ttsService, resolver, assetLoader, wavResponseRegistry);
        ReflectionTestUtils.setField(service, "preRecordedEnabled", true);
    }

    @Test
    void usesPreRecordedAudioWhenResolverReturnsActiveAsset() {
        byte[] expected = new byte[]{1, 2, 3};
        when(resolver.resolve(eq("wake_response"), eq("К вашим услугам, сэр."), eq("ru-RU"), eq(null), anyMap()))
                .thenReturn(VoiceResponse.preRecorded("К вашим услугам, сэр.", "ru/system/always_at_service_sir", null));
        when(assetLoader.load("ru/system/always_at_service_sir")).thenReturn(expected);

        byte[] audio = service.resolveAndGetAudio(
                "wake_response",
                "К вашим услугам, сэр.",
                "ru-RU",
                "ru-RU",
                "ru-RU-Wavenet-A");

        assertArrayEquals(expected, audio);
        verifyNoInteractions(ttsService);
    }

    @Test
    void fallsBackToTtsWhenConfiguredAssetCannotBeLoaded() {
        byte[] expected = new byte[]{9, 8, 7};
        when(resolver.resolve(eq("open_url"), eq("Загружаю, сэр."), eq("ru-RU"), eq(null), anyMap()))
                .thenReturn(VoiceResponse.preRecorded("Загружаю, сэр.", "ru/assistant/loading_sir", null));
        when(assetLoader.load("ru/assistant/loading_sir")).thenReturn(null);
        when(ttsService.synthesize("Загружаю, сэр.", "ru-RU", "ru-RU-Wavenet-A", 1.0, 0.0)).thenReturn(expected);

        byte[] audio = service.resolveAndGetAudio(
                "open_url",
                "Загружаю, сэр.",
                "ru-RU",
                "ru-RU",
                "ru-RU-Wavenet-A");

        assertArrayEquals(expected, audio);
    }

    @Test
    void returnsNullWhenSilentModeIsRequested() {
        when(resolver.resolve(eq("noop"), eq(""), eq("ru-RU"), eq(null), anyMap()))
                .thenReturn(VoiceResponse.silent(""));

        byte[] audio = service.resolveAndGetAudio(
                "noop",
                null,
                "ru-RU",
                "ru-RU",
                "ru-RU-Wavenet-A");

        assertNull(audio);
        verifyNoInteractions(ttsService);
    }

    @Test
    void usesRuleResponseRegistryBeforeTtsFallback() {
        byte[] expected = new byte[]{4, 5, 6};
        when(wavResponseRegistry.lookupAssetId("loading_sir", "ru-RU")).thenReturn("ru/assistant/loading_sir");
        when(assetLoader.load("ru/assistant/loading_sir")).thenReturn(expected);

        byte[] audio = service.resolveRuleResponseAudio(
                "loading_sir",
                "Загружаю, сэр.",
                "ru-RU",
                "ru-RU",
                "ru-RU-Wavenet-A");

        assertArrayEquals(expected, audio);
        verifyNoInteractions(ttsService);
    }
}
