package org.jarvis.media.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.jarvis.media.probe.ProbeRequest;
import org.jarvis.media.probe.ProbeResult;
import org.jarvis.media.probe.ProbeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Synchronous stream-detection endpoint. ffprobe metadata is fast, so this returns
 * the structured streams directly rather than spawning a job.
 */
@RestController
@RequestMapping("/api/v1/media")
public class ProbeController {

    private final ProbeService probeService;
    private final MediaFeatureGate gate;

    public ProbeController(ProbeService probeService, MediaFeatureGate gate) {
        this.probeService = probeService;
        this.gate = gate;
    }

    @PostMapping("/probe")
    public ProbeResult probe(@Valid @RequestBody ProbeRequest request, HttpServletRequest http) {
        gate.ensureEnabled();
        UserContext.requireUserId(http);
        return probeService.probe(request);
    }
}
