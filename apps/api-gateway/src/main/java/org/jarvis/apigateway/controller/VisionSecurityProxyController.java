package org.jarvis.apigateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.client.VisionSecurityClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/vision-security")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "services.vision-security", name = "enabled", havingValue = "true")
public class VisionSecurityProxyController {

    private final VisionSecurityClient client;

    @Value("${services.vision-security.url}")
    private String visionSecurityUrl;

    @GetMapping("/status")
    public ResponseEntity<String> status() {
        log.info("Proxying GET /api/v1/vision-security/status to {}", visionSecurityUrl);
        return client.getStatus();
    }

    @GetMapping("/config")
    public ResponseEntity<String> config() {
        return client.getConfig();
    }

    @PostMapping("/monitoring/start")
    public ResponseEntity<String> startMonitoring() {
        return client.startMonitoring();
    }

    @PostMapping("/monitoring/stop")
    public ResponseEntity<String> stopMonitoring() {
        return client.stopMonitoring();
    }

    @PostMapping(value = "/enrollment/capture", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> captureEnrollment(@RequestBody(required = false) String body) {
        return client.captureEnrollment(body == null || body.isBlank() ? "{}" : body);
    }

    @PostMapping(value = "/enrollment/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> importEnrollment(@RequestBody String body) {
        return client.importEnrollment(body);
    }

    @PostMapping("/enrollment/reset")
    public ResponseEntity<String> resetEnrollment() {
        return client.resetEnrollment();
    }

    @GetMapping("/incidents")
    public ResponseEntity<String> incidents(@RequestParam(defaultValue = "20") int limit) {
        return client.listIncidents(limit);
    }

    @GetMapping("/incidents/{incidentId}")
    public ResponseEntity<String> incident(@PathVariable String incidentId) {
        return client.getIncident(incidentId);
    }

    @PostMapping("/pipeline/capture")
    public ResponseEntity<String> capturePipeline() {
        return client.capturePipeline();
    }

    @PostMapping("/alerts/test")
    public ResponseEntity<String> sendTestAlert() {
        return client.sendTestAlert();
    }
}
