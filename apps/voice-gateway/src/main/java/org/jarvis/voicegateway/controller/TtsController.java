package org.jarvis.voicegateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.dto.TtsRequest;
import org.jarvis.voicegateway.service.TtsService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller for text-to-speech synthesis.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/voice")
@RequiredArgsConstructor
public class TtsController {

    private final TtsService ttsService;

    /**
     * Synthesize text to speech.
     * Returns WAV audio data.
     */
    @PostMapping(value = "/synthesize", produces = "audio/wav")
    public ResponseEntity<byte[]> synthesize(@RequestBody TtsRequest request) {
        log.info("TTS request: text length={}, language={}",
                request.getText() != null ? request.getText().length() : 0,
                request.getLanguageCode());

        TtsService.SynthesisResult synthesis = ttsService.synthesizeDetailed(
                request.getText(),
                request.getLanguageCode() != null ? request.getLanguageCode() : "ru-RU",
                request.getVoiceName() != null ? request.getVoiceName() : "ru-RU-Wavenet-A",
                request.getSpeakingRate() != null ? request.getSpeakingRate() : 1.0,
                request.getPitch() != null ? request.getPitch() : 0.0);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "audio/wav")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(synthesis.audioData().length))
                .header("X-Jarvis-Tts-Configured-Provider", synthesis.configuredProvider())
                .header("X-Jarvis-Tts-Actual-Provider", synthesis.actualProvider())
                .header("X-Jarvis-Tts-Status", synthesis.status())
                .header("X-Jarvis-Tts-Reason", synthesis.reason())
                .body(synthesis.audioData());
    }

    /**
     * Quick test endpoint for TTS.
     */
    @GetMapping("/synthesize/test")
    public ResponseEntity<byte[]> testSynthesize(@RequestParam(defaultValue = "Привет! Я Джарвис.") String text) {
        TtsRequest request = TtsRequest.builder()
                .text(text)
                .languageCode("ru-RU")
                .build();
        return synthesize(request);
    }
}
