package org.jarvis.apigateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.client.PcControlClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/pc")
@RequiredArgsConstructor
public class PcControlProxyController {

    private final PcControlClient pcClient;
    @Value("${services.pc-control.url}")
    private String pcControlUrl;

    @PostMapping("/action")
    public ResponseEntity<String> executeAction(
            @RequestHeader(value = "X-Smoke-Run-Id", required = false) String smokeRunId,
            @RequestBody Map<String, Object> request) {
        log.info("Proxying POST /api/v1/pc/action to {}: actionType={}, smokeRunId={}",
                pcControlUrl, request.get("actionType"), smokeRunId != null ? smokeRunId : "none");
        return pcClient.executeAction(request);
    }
}
