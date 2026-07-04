package org.jarvis.security.controller;

import lombok.RequiredArgsConstructor;
import org.jarvis.security.service.PrivacyModeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Privacy / panic mode toggle (safe, reversible). */
@RestController
@RequestMapping("/auth/privacy")
@RequiredArgsConstructor
public class PrivacyController {

    private final PrivacyModeService privacyMode;

    @PostMapping("/on")
    public Map<String, Object> on() {
        privacyMode.enable();
        return status();
    }

    @PostMapping("/off")
    public Map<String, Object> off() {
        privacyMode.disable();
        return status();
    }

    @GetMapping
    public Map<String, Object> get() {
        return status();
    }

    private Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", privacyMode.isActive());
        out.put("since", privacyMode.since() == null ? null : privacyMode.since().toString());
        return out;
    }
}
