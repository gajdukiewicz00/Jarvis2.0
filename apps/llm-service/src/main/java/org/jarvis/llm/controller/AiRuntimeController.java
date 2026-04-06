package org.jarvis.llm.controller;

import lombok.RequiredArgsConstructor;
import org.jarvis.llm.service.AiRuntimeStatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/llm")
@RequiredArgsConstructor
public class AiRuntimeController {

    private final AiRuntimeStatusService aiRuntimeStatusService;

    @GetMapping("/runtime")
    public ResponseEntity<Map<String, Object>> runtime() {
        return ResponseEntity.ok(aiRuntimeStatusService.describe());
    }
}
