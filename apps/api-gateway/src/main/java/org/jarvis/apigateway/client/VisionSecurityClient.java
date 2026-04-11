package org.jarvis.apigateway.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "vision-security", url = "${services.vision-security.url:http://localhost:8094}")
public interface VisionSecurityClient {

    @GetMapping("/api/v1/vision-security/status")
    ResponseEntity<String> getStatus();

    @GetMapping("/api/v1/vision-security/config")
    ResponseEntity<String> getConfig();

    @PostMapping("/api/v1/vision-security/monitoring/start")
    ResponseEntity<String> startMonitoring();

    @PostMapping("/api/v1/vision-security/monitoring/stop")
    ResponseEntity<String> stopMonitoring();

    @PostMapping(
            value = "/api/v1/vision-security/enrollment/capture",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ResponseEntity<String> captureEnrollment(@RequestBody String body);

    @PostMapping(
            value = "/api/v1/vision-security/enrollment/import",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    ResponseEntity<String> importEnrollment(@RequestBody String body);

    @PostMapping("/api/v1/vision-security/enrollment/reset")
    ResponseEntity<String> resetEnrollment();

    @GetMapping("/api/v1/vision-security/incidents")
    ResponseEntity<String> listIncidents(@RequestParam("limit") int limit);

    @GetMapping("/api/v1/vision-security/incidents/{incidentId}")
    ResponseEntity<String> getIncident(@PathVariable("incidentId") String incidentId);

    @PostMapping("/api/v1/vision-security/pipeline/capture")
    ResponseEntity<String> capturePipeline();

    @PostMapping("/api/v1/vision-security/alerts/test")
    ResponseEntity<String> sendTestAlert();
}
