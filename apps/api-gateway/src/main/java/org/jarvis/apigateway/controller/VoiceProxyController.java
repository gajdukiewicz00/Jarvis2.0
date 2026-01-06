package org.jarvis.apigateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.client.VoiceGatewayClient;
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

    @PostMapping("/transcribe")
    public ResponseEntity<Map<String, Object>> transcribe(@RequestParam("file") MultipartFile file) {
        log.info("Proxying POST /api/v1/voice/transcribe, file size: {} bytes", file.getSize());
        return voiceClient.transcribe(file);
    }

    @PostMapping(value = "/transcribe/stream", consumes = "application/octet-stream")
    public ResponseEntity<String> transcribeStream(@RequestBody byte[] audioData) {
        log.info("Proxying POST /api/v1/voice/transcribe/stream, data size: {} bytes", audioData.length);
        return voiceClient.transcribeStream(audioData);
    }

    @PostMapping("/command")
    public ResponseEntity<String> command(@RequestBody Map<String, String> request) {
        log.info("Proxying POST /api/v1/voice/command: {}", request.get("text"));
        return voiceClient.command(request);
    }
}
