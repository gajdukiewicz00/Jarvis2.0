package org.jarvis.voicegateway.voice;

import lombok.Builder;
import lombok.Value;

/**
 * Result of voice output resolution.
 * Text is always populated for display/logging; audio comes from asset or TTS.
 */
@Value
@Builder
public class VoiceResponse {
    VoicePlaybackMode mode;
    /** Display/text for logging and optional on-screen display */
    String text;
    /** Asset ID when mode=PRE_RECORDED (e.g. ru/system/wake_yes_sir) */
    String audioAssetId;
    /** Pre-loaded audio bytes when mode=PRE_RECORDED, null otherwise */
    byte[] audioData;

    public static VoiceResponse tts(String text) {
        return VoiceResponse.builder()
                .mode(VoicePlaybackMode.TTS)
                .text(text)
                .audioAssetId(null)
                .audioData(null)
                .build();
    }

    public static VoiceResponse preRecorded(String text, String assetId, byte[] audioData) {
        return VoiceResponse.builder()
                .mode(VoicePlaybackMode.PRE_RECORDED)
                .text(text)
                .audioAssetId(assetId)
                .audioData(audioData)
                .build();
    }

    public static VoiceResponse silent(String text) {
        return VoiceResponse.builder()
                .mode(VoicePlaybackMode.SILENT)
                .text(text)
                .audioAssetId(null)
                .audioData(null)
                .build();
    }
}
