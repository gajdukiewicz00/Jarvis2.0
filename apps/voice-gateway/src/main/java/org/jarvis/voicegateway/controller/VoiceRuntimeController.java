package org.jarvis.voicegateway.controller;

import lombok.RequiredArgsConstructor;
import org.jarvis.voicegateway.service.VoiceRuntimeStatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/voice")
@RequiredArgsConstructor
public class VoiceRuntimeController {

    private final VoiceRuntimeStatusService voiceRuntimeStatusService;

    @GetMapping("/runtime")
    public ResponseEntity<Map<String, Object>> runtime() {
        return ResponseEntity.ok(voiceRuntimeStatusService.describe());
    }

    @GetMapping("/diagnostics")
    public ResponseEntity<Map<String, Object>> diagnostics() {
        return ResponseEntity.ok(voiceRuntimeStatusService.describeDiagnostics());
    }
}
