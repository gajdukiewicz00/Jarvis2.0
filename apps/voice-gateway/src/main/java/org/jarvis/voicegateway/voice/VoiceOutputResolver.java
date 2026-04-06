package org.jarvis.voicegateway.voice;

import java.util.Locale;
import java.util.Map;

/**
 * Resolves how to deliver voice output for a given context.
 * Routes by intent/action, status, error code, and locale.
 */
public interface VoiceOutputResolver {

    /**
     * Resolve voice output for a response.
     *
     * @param action     Intent/action name (e.g. VOLUME_UP, ACK_SUCCESS)
     * @param text       Response text from orchestrator (for TTS fallback)
     * @param locale     Language/locale (e.g. ru-RU, en-US)
     * @param errorCode  Optional error code (null if success)
     * @param params     Optional extra context (e.g. system event, persona)
     * @return VoiceResponse with mode and optional pre-recorded audio
     */
    VoiceResponse resolve(
            String action,
            String text,
            String locale,
            String errorCode,
            Map<String, Object> params);
}
