package org.jarvis.voicegateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request for text-to-speech synthesis.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TtsRequest {

    private String text;

    @lombok.Builder.Default
    private String languageCode = "ru-RU";

    @lombok.Builder.Default
    private String voiceName = "ru-RU-Wavenet-A";

    @lombok.Builder.Default
    private Double speakingRate = 1.0;

    @lombok.Builder.Default
    private Double pitch = 0.0;
}
