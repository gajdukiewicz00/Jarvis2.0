package org.jarvis.visionsecurity.controller;

import lombok.RequiredArgsConstructor;
import org.jarvis.visionsecurity.model.EmailDelivery;
import org.jarvis.visionsecurity.model.EnrollmentResult;
import org.jarvis.visionsecurity.model.IncidentRecord;
import org.jarvis.visionsecurity.model.PipelineSnapshotResult;
import org.jarvis.visionsecurity.model.VisionSecurityConfigView;
import org.jarvis.visionsecurity.model.VisionSecurityStatus;
import org.jarvis.visionsecurity.service.VisionSecurityManager;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/vision-security")
@RequiredArgsConstructor
public class VisionSecurityController {

    private final VisionSecurityManager manager;

    @GetMapping("/status")
    public ResponseEntity<VisionSecurityStatus> status(Authentication authentication) {
        return ResponseEntity.ok(manager.statusFor(authentication.getName()));
    }

    @GetMapping("/config")
    public ResponseEntity<VisionSecurityConfigView> config() {
        return ResponseEntity.ok(manager.configView());
    }

    @PostMapping("/monitoring/start")
    public ResponseEntity<VisionSecurityStatus> startMonitoring(Authentication authentication) {
        return ResponseEntity.ok(manager.startMonitoring(authentication.getName()));
    }

    @PostMapping("/monitoring/stop")
    public ResponseEntity<VisionSecurityStatus> stopMonitoring(Authentication authentication) {
        return ResponseEntity.ok(manager.stopMonitoring(authentication.getName()));
    }

    @PostMapping("/enrollment/capture")
    public ResponseEntity<EnrollmentResult> captureEnrollment(
            Authentication authentication,
            @RequestBody(required = false) EnrollmentCaptureRequest request
    ) throws Exception {
        Integer sampleCount = request == null ? null : request.sampleCount();
        return ResponseEntity.ok(manager.captureEnrollment(authentication.getName(), sampleCount));
    }

    @PostMapping("/enrollment/import")
    public ResponseEntity<EnrollmentResult> importEnrollment(
            Authentication authentication,
            @RequestBody EnrollmentImportRequest request
    ) throws Exception {
        return ResponseEntity.ok(manager.importEnrollmentFromDataset(
                authentication.getName(),
                java.nio.file.Path.of(request.datasetDirectory())
        ));
    }

    @PostMapping("/enrollment/reset")
    public ResponseEntity<VisionSecurityStatus> resetEnrollment(Authentication authentication) throws Exception {
        return ResponseEntity.ok(manager.resetEnrollment(authentication.getName()));
    }

    @GetMapping("/incidents")
    public ResponseEntity<List<IncidentRecord>> incidents(
            Authentication authentication,
            @RequestParam(defaultValue = "20") int limit
    ) throws Exception {
        return ResponseEntity.ok(manager.listIncidents(authentication.getName(), limit));
    }

    @GetMapping("/incidents/{incidentId}")
    public ResponseEntity<IncidentRecord> incident(
            Authentication authentication,
            @PathVariable String incidentId
    ) throws Exception {
        return ResponseEntity.ok(manager.incident(authentication.getName(), incidentId));
    }

    @PostMapping("/pipeline/capture")
    public ResponseEntity<PipelineSnapshotResult> capturePipeline(Authentication authentication) throws Exception {
        return ResponseEntity.ok(manager.capturePipelineSnapshot(authentication.getName()));
    }

    @PostMapping("/alerts/test")
    public ResponseEntity<Map<String, Object>> sendTestAlert(Authentication authentication) {
        EmailDelivery delivery = manager.sendTestAlert(authentication.getName());
        return ResponseEntity.ok(Map.of(
                "attempted", delivery.attempted(),
                "sent", delivery.sent(),
                "message", delivery.message()
        ));
    }

    public record EnrollmentCaptureRequest(Integer sampleCount) {
    }

    public record EnrollmentImportRequest(String datasetDirectory) {
    }
}
