package org.jarvis.voicegateway.voice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.jarvis.voicegateway.service.TtsService;

import java.util.Map;

/**
 * Hybrid voice output: pre-recorded .wav when available, TTS fallback.
 * Uses VoiceOutputResolver for routing, VoiceAssetLoader for .wav, TtsService for synthesis.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceOutputService {

    private final TtsService ttsService;
    private final RuleBasedVoiceOutputResolver resolver;
    private final VoiceAssetLoader assetLoader;
    private final WavResponseRegistry wavResponseRegistry;

    @Value("${jarvis.voice.pre-recorded.enabled:true}")
    private boolean preRecordedEnabled;

    /**
     * Resolve and produce audio for a voice response.
     * If pre-recorded asset exists and is enabled -> use it.
     * Else -> synthesize via TTS.
     *
     * @param action       Intent/action (e.g. VOLUME_UP)
     * @param text         Response text (for display and TTS fallback)
     * @param locale       Language (ru-RU, en-US, etc.)
     * @param languageCode For TTS (e.g. ru-RU)
     * @param voiceName    For TTS (e.g. ru-RU-Wavenet-A)
     */
    public byte[] resolveAndGetAudio(String action, String text, String locale,
                                     String languageCode, String voiceName) {
        String safeText = text != null ? text : "";

        VoiceResponse resolution = resolver.resolve(action, safeText, locale, null, Map.of());
        if (resolution.getMode() == VoicePlaybackMode.SILENT) {
            log.debug("🔇 Voice output suppressed for action={}", action);
            return null;
        }

        byte[] preRecordedAudio = loadPreRecordedAudio(resolution.getAudioAssetId());
        if (preRecordedAudio != null) {
            return preRecordedAudio;
        }

        return synthesizeOrNull(action, safeText, languageCode, voiceName);
    }

    /**
     * Resolve audio for the new rule-based command layer by response key.
     * If the response key has an associated WAV -> use it, otherwise synthesize
     * the resolved text.
     */
    public byte[] resolveRuleResponseAudio(String responseKey, String text, String locale,
                                           String languageCode, String voiceName) {
        String safeText = text != null ? text : "";
        String assetId = wavResponseRegistry.lookupAssetId(responseKey, locale);
        byte[] preRecordedAudio = loadPreRecordedAudio(assetId);
        if (preRecordedAudio != null) {
            return preRecordedAudio;
        }

        if (safeText.isBlank()) {
            safeText = wavResponseRegistry.lookupText(responseKey, locale);
        }
        return synthesizeOrNull(responseKey != null ? responseKey : "rule_response", safeText, languageCode, voiceName);
    }

    private byte[] loadPreRecordedAudio(String assetId) {
        if (!preRecordedEnabled || assetId == null || assetId.isBlank()) {
            return null;
        }
        byte[] audio = assetLoader.load(assetId);
        if (audio != null && audio.length > 0) {
            log.info("🔊 Using pre-recorded asset: {} ({} bytes), correlationId in caller", assetId, audio.length);
            return audio;
        }
        log.debug("Pre-recorded asset not found: {}, falling back to TTS", assetId);
        return null;
    }

    private byte[] synthesizeOrNull(String action, String text, String languageCode, String voiceName) {
        if (text == null || text.isBlank()) {
            log.warn("VoiceOutputService: empty text and no usable pre-recorded asset for action={}", action);
            return null;
        }
        log.debug("🔊 Synthesizing via TTS: action={}, textLength={}", action, text.length());
        return ttsService.synthesize(text, languageCode, voiceName, 1.0, 0.0);
    }
}
