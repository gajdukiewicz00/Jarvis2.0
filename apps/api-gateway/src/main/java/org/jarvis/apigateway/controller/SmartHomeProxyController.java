package org.jarvis.apigateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.client.SmartHomeClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/smarthome")
@RequiredArgsConstructor
public class SmartHomeProxyController {

    private final SmartHomeClient smartHomeClient;

    @GetMapping("/devices")
    public ResponseEntity<String> listDevices() {
        log.info("Proxying GET /api/v1/smarthome/devices");
        return smartHomeClient.listDevices();
    }

    @GetMapping("/devices/{id}")
    public ResponseEntity<String> getDevice(@PathVariable("id") String deviceId) {
        log.info("Proxying GET /api/v1/smarthome/devices/{}", deviceId);
        return smartHomeClient.getDevice(deviceId);
    }

    @PostMapping("/devices/{id}/action")
    public ResponseEntity<String> deviceAction(@PathVariable("id") String deviceId,
            @RequestBody Map<String, String> request) {
        log.info("Proxying POST /api/v1/smarthome/devices/{}/action: {}", deviceId, request.get("action"));
        return smartHomeClient.deviceAction(deviceId, request);
    }
}
