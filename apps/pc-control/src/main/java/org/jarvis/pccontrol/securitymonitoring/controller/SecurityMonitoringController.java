package org.jarvis.pccontrol.securitymonitoring.controller;

import lombok.RequiredArgsConstructor;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringCheckReport;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringStatusSnapshot;
import org.jarvis.pccontrol.securitymonitoring.service.SecurityMonitoringService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pc/security-monitoring")
@RequiredArgsConstructor
public class SecurityMonitoringController {

    private final SecurityMonitoringService securityMonitoringService;

    @GetMapping("/status")
    public ResponseEntity<MonitoringStatusSnapshot> status() {
        return ResponseEntity.ok(securityMonitoringService.status());
    }

    @PostMapping("/check")
    public ResponseEntity<MonitoringCheckReport> runManualCheck() {
        return ResponseEntity.ok(securityMonitoringService.runCheck("manual"));
    }
}
