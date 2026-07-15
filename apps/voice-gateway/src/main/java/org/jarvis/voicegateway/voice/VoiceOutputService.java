package org.jarvis.voicegateway.voice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.jarvis.voicegateway.service.TtsService;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Hybrid voice output: pre-recorded .wav when available, TTS fallback.
 * Uses VoiceOutputResolver for routing, VoiceAssetLoader for .wav, TtsService for synthesis.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceOutputService {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.,!?…]+$");
    private static final Pattern TRAILING_VOCATIVE = Pattern.compile("[,\\s]*(?:сэр|sir)$");

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

        // NOTE: keyed purely on action -> asset (voice-routing-rules.yaml). The resolver echoes the
        // caller text back into VoiceResponse.text; it does NOT expose the WAV recording's canonical
        // phrase, so there is no independent text to run the alignment guard against here. This path
        // is action-keyed with a static per-action asset, so per contract it is left as-is. The
        // alignment guard lives in resolveRuleResponseAudio, where lookupText() yields the canonical
        // phrase to compare with the dynamic final text.
        byte[] preRecordedAudio = loadPreRecordedAudio(resolution.getAudioAssetId());
        if (preRecordedAudio != null) {
            return preRecordedAudio;
        }

        return synthesizeOrNull(action, safeText, languageCode, voiceName);
    }

    /**
     * Resolve audio for the new rule-based command layer by response key.
     *
     * <p>The spoken audio must always match the final text response. Callers may pass a dynamic
     * {@code text} (planner/finance summary, "Ищу на YouTube: X", "Громкость установлена на N%")
     * while the WAV is chosen from the static {@code responseKey}. To prevent a stale/wrong WAV from
     * playing, the WAV's canonical phrase ({@link WavResponseRegistry#lookupText}) is compared with
     * the caller text using a normalized comparison. On mismatch the WAV is skipped and the exact
     * caller text is synthesized instead.
     */
    public byte[] resolveRuleResponseAudio(String responseKey, String text, String locale,
                                           String languageCode, String voiceName) {
        String safeText = text != null ? text : "";
        String assetId = wavResponseRegistry.lookupAssetId(responseKey, locale);
        String wavCanonicalText = wavResponseRegistry.lookupText(responseKey, locale);

        boolean callerTextPresent = !safeText.isBlank();
        boolean wavCandidate = preRecordedEnabled && assetId != null && !assetId.isBlank();
        boolean canonicalPresent = wavCanonicalText != null && !wavCanonicalText.isBlank();
        boolean mismatchDetected = wavCandidate
                && callerTextPresent
                && canonicalPresent
                && !textMatchesWav(safeText, wavCanonicalText);

        // Aligned (or no caller text to diverge): play the WAV. On mismatch we never load it.
        if (wavCandidate && !mismatchDetected) {
            byte[] preRecordedAudio = loadPreRecordedAudio(assetId);
            if (preRecordedAudio != null) {
                String spokenText = canonicalPresent ? wavCanonicalText : safeText;
                logAudioDecision(safeText, spokenText, responseKey, "wav", assetId, false);
                return preRecordedAudio;
            }
            // Asset configured but not loadable -> fall through to TTS.
        }

        // Mismatch, no WAV, or unloadable asset -> synthesize the exact final text (canonical WAV
        // phrase only when the caller supplied no text).
        String ttsText = callerTextPresent ? safeText : (canonicalPresent ? wavCanonicalText : "");
        byte[] synthesized = synthesizeOrNull(
                responseKey != null ? responseKey : "rule_response", ttsText, languageCode, voiceName);
        logAudioDecision(safeText, ttsText, responseKey, synthesized != null ? "tts" : "none",
                null, mismatchDetected);
        return synthesized;
    }

    /**
     * True when the caller's final text matches the WAV recording's canonical phrase after
     * normalization: lowercase, ё→е, trimmed, whitespace collapsed, trailing punctuation (.,!?…)
     * and the vocative (", сэр"/"sir") stripped. Returns false when the canonical phrase is absent
     * (no evidence of alignment) so the caller falls back to synthesizing the exact final text.
     */
    private boolean textMatchesWav(String finalText, String wavCanonicalText) {
        if (wavCanonicalText == null || wavCanonicalText.isBlank()) {
            return false;
        }
        String normalizedFinal = normalizeForComparison(finalText);
        if (normalizedFinal.isEmpty()) {
            return false;
        }
        return normalizedFinal.equals(normalizeForComparison(wavCanonicalText));
    }

    private String normalizeForComparison(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ");
        normalized = TRAILING_PUNCTUATION.matcher(normalized).replaceAll("").trim();
        normalized = TRAILING_VOCATIVE.matcher(normalized).replaceAll("").trim();
        normalized = TRAILING_PUNCTUATION.matcher(normalized).replaceAll("").trim();
        return normalized;
    }

    private void logAudioDecision(String finalText, String audioText, String responseKey,
                                  String audioSource, String wavFile, boolean mismatchDetected) {
        log.info(
                "voice-audio-decision finalText='{}' audioText='{}' responseKey={} audioSource={} wavFile={} mismatchDetected={}",
                finalText, audioText, responseKey, audioSource, wavFile, mismatchDetected);
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
