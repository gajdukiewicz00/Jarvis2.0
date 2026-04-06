package org.jarvis.pccontrol.securitymonitoring.model;

import java.awt.image.BufferedImage;
import java.time.Instant;

public record CapturedFrame(
        BufferedImage image,
        String provider,
        String device,
        Instant capturedAt) {

    public CapturedFrame {
        if (image == null) {
            throw new IllegalArgumentException("Captured frame image is required");
        }
        provider = provider == null || provider.isBlank() ? "unknown" : provider;
        device = device == null || device.isBlank() ? "default" : device;
        capturedAt = capturedAt == null ? Instant.now() : capturedAt;
    }
}
