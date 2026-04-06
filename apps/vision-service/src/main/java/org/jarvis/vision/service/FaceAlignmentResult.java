package org.jarvis.vision.service;

import java.awt.image.BufferedImage;
import java.util.Map;

public record FaceAlignmentResult(
        boolean available,
        boolean applied,
        String provider,
        String mode,
        String message,
        BufferedImage faceImage,
        Map<String, String> diagnostics) {

    public FaceAlignmentResult {
        provider = provider == null ? "" : provider;
        mode = mode == null ? "" : mode;
        message = message == null ? "" : message;
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
