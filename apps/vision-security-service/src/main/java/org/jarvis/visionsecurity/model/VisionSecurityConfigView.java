package org.jarvis.visionsecurity.model;

public record VisionSecurityConfigView(
        long checkIntervalMs,
        int debounceUnknownFrames,
        long alertCooldownSeconds,
        String storageRoot,
        String emailRecipient,
        String ocrLanguage,
        boolean preferGpu,
        String displayServer
) {
}
