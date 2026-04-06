package org.jarvis.apigateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.client.VoiceGatewayClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/voice")
@RequiredArgsConstructor
public class VoiceProxyController {

    private final VoiceGatewayClient voiceClient;
    @Value("${services.voice-gateway.url}")
    private String voiceGatewayUrl;

    @PostMapping("/transcribe")
    public ResponseEntity<Map<String, Object>> transcribe(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "language", required = false) String languageCode) {
        log.info("Proxying POST /api/v1/voice/transcribe, file size: {} bytes, language={}",
                file.getSize(),
                languageCode != null ? languageCode : "default");
        return voiceClient.transcribe(file, languageCode);
    }

    @PostMapping(value = "/transcribe/stream", consumes = "application/octet-stream")
    public ResponseEntity<Map<String, Object>> transcribeStream(
            @RequestBody byte[] audioData,
            @RequestParam(value = "language", required = false) String languageCode) {
        log.info("Proxying POST /api/v1/voice/transcribe/stream, data size: {} bytes, language={}",
                audioData.length,
                languageCode != null ? languageCode : "default");
        return voiceClient.transcribeStream(audioData, languageCode);
    }

    @PostMapping("/command")
    public ResponseEntity<String> command(
            @RequestHeader(value = "X-Smoke-Run-Id", required = false) String smokeRunId,
            @RequestBody Map<String, String> request) {
        log.info("Proxying POST /api/v1/voice/command to {}: text={}, smokeRunId={}",
                voiceGatewayUrl,
                request.get("text"),
                smokeRunId != null ? smokeRunId : "none");
        return voiceClient.command(request);
    }

    @PostMapping(value = "/synthesize", produces = "audio/wav")
    public ResponseEntity<byte[]> synthesize(@RequestBody Map<String, Object> request) {
        log.info("Proxying POST /api/v1/voice/synthesize to {}: textLength={}",
                voiceGatewayUrl,
                request.get("text") != null ? String.valueOf(request.get("text")).length() : 0);
        return voiceClient.synthesize(request);
    }

    @GetMapping("/runtime")
    public ResponseEntity<Map<String, Object>> runtime() {
        log.info("Proxying GET /api/v1/voice/runtime to {}", voiceGatewayUrl);
        return voiceClient.runtime();
    }
}
