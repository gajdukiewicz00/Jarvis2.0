package org.jarvis.pccontrol.controller;

import lombok.RequiredArgsConstructor;
import org.jarvis.pccontrol.model.DesktopCapabilities;
import org.jarvis.pccontrol.service.CapabilityDetector;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pc/desktop")
@RequiredArgsConstructor
public class DesktopCapabilitiesController {

    private final CapabilityDetector capabilityDetector;

    @GetMapping("/capabilities")
    public ResponseEntity<DesktopCapabilities> getCapabilities() {
        return ResponseEntity.ok(capabilityDetector.detect());
    }
}
