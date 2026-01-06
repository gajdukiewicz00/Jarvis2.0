package org.jarvis.apigateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.client.NlpServiceClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/nlp")
@RequiredArgsConstructor
public class NlpProxyController {

    private final NlpServiceClient nlpClient;

    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody Map<String, String> request) {
        log.info("Proxying POST /api/v1/nlp/analyze: {}", request.get("text"));
        return nlpClient.analyze(request);
    }
}
