package org.jarvis.apigateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.client.PcControlClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/pc")
@RequiredArgsConstructor
public class PcControlProxyController {

    private final PcControlClient pcClient;

    @PostMapping("/action")
    public ResponseEntity<String> executeAction(@RequestBody Map<String, Object> request) {
        log.info("Proxying POST /api/v1/pc/action: {}", request.get("actionType"));
        return pcClient.executeAction(request);
    }
}
