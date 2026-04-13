package org.jarvis.apigateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jarvis.apigateway.client.VoiceGatewayClient;
import org.jarvis.apigateway.proxy.DownstreamProxyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/voice")
@RequiredArgsConstructor
public class VoiceProxyController {

    private final DownstreamProxyService downstreamProxyService;
    private final VoiceGatewayClient voiceGatewayClient;

    @Value("${services.voice-gateway.url}")
    private String voiceGatewayUrl;

    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> transcribe(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "language", required = false) String languageCode) {
        return voiceGatewayClient.transcribe(file, languageCode);
    }

    @PostMapping(value = "/transcribe/stream", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Map<String, Object>> transcribeStream(
            @RequestBody byte[] audioData,
            @RequestParam(value = "language", required = false) String languageCode) {
        return voiceGatewayClient.transcribeStream(audioData, languageCode);
    }

    @RequestMapping(value = {"", "/**"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) {
        return downstreamProxyService.forward(request, "voice-gateway", voiceGatewayUrl);
    }
}
