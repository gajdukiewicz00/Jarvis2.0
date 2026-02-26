package org.jarvis.voicegateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.dto.TtsRequest;
import org.jarvis.voicegateway.service.TtsService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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

        try {
            byte[] audioData = ttsService.synthesize(
                    request.getText(),
                    request.getLanguageCode() != null ? request.getLanguageCode() : "ru-RU",
                    request.getVoiceName() != null ? request.getVoiceName() : "ru-RU-Wavenet-A",
                    request.getSpeakingRate() != null ? request.getSpeakingRate() : 1.0,
                    request.getPitch() != null ? request.getPitch() : 0.0);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "audio/wav")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(audioData.length))
                    .body(audioData);

        } catch (IllegalArgumentException e) {
            log.error("TTS synthesis failed due to invalid arguments", e);
            return ResponseEntity.badRequest().body(null);
        } catch (RuntimeException e) {
            log.error("TTS synthesis failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(null);
        }
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
