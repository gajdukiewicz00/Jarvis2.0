package org.jarvis.apigateway.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@FeignClient(name = "voice-gateway", url = "${services.voice-gateway.url:http://localhost:8081}")
public interface VoiceGatewayClient {

    @PostMapping(value = "/api/v1/voice/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Map<String, Object>> transcribe(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "language", required = false) String languageCode);

    @PostMapping(value = "/api/v1/voice/transcribe/stream", consumes = "application/octet-stream")
    ResponseEntity<Map<String, Object>> transcribeStream(
            @RequestBody byte[] audioData,
            @RequestParam(value = "language", required = false) String languageCode);

    @PostMapping("/api/v1/voice/command")
    ResponseEntity<String> command(@RequestBody Map<String, String> request);

    @PostMapping(value = "/api/v1/voice/synthesize", produces = "audio/wav")
    ResponseEntity<byte[]> synthesize(@RequestBody Map<String, Object> request);

    @GetMapping("/api/v1/voice/runtime")
    ResponseEntity<Map<String, Object>> runtime();
}
