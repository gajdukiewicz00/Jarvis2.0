package org.jarvis.vision.phase10;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 10 — vision subsystem status surface for the desktop panel.
 *
 * <p>Returns a small JSON payload that summarises whether the host has
 * the dependencies the existing services need: webcam (Mat / VideoCapture),
 * tesseract OCR binary, demo-mode flag, retention status. The Phase 6
 * StatusAggregator polls this endpoint every 15s.</p>
 */
@RestController
@RequestMapping("/api/v1/vision")
@RequiredArgsConstructor
public class VisionStatusController {

    private final DemoModeProperties demoMode;
    private final VisionRetentionProperties retention;

    private static final AtomicLong LAST_CHECK = new AtomicLong();
    private static volatile boolean tesseractAvailable;
    private static volatile boolean opencvAvailable;

    @GetMapping("/status")
    public Map<String, Object> status() {
        refreshProbes();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "vision-security-service");
        body.put("checkedAt", Instant.ofEpochMilli(LAST_CHECK.get()).toString());
        body.put("demoMode", demoMode.isEnabled());
        body.put("opencvAvailable", opencvAvailable);
        body.put("tesseractAvailable", tesseractAvailable);
        body.put("retention", Map.of(
                "enabled", retention.isEnabled(),
                "days", retention.getDays(),
                "root", retention.getRoot()
        ));
        return body;
    }

    private void refreshProbes() {
        long now = System.currentTimeMillis();
        long previous = LAST_CHECK.get();
        // throttle expensive probes to ~30s
        if (now - previous < 30_000) {
            return;
        }
        if (!LAST_CHECK.compareAndSet(previous, now)) {
            return;
        }
        opencvAvailable = probeClass("org.opencv.videoio.VideoCapture")
                || probeClass("org.bytedeco.opencv.opencv_videoio.VideoCapture");
        tesseractAvailable = probeBinary("tesseract");
    }

    private boolean probeClass(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    private boolean probeBinary(String binary) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return false;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            File f = new File(dir, binary);
            if (f.canExecute()) return true;
        }
        return false;
    }
}
