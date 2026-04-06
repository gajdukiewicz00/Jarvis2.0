package org.jarvis.pccontrol.securitymonitoring.model;

public record WebcamCaptureResult(
        boolean operational,
        String provider,
        String message,
        CapturedFrame frame) {

    public WebcamCaptureResult {
        provider = provider == null || provider.isBlank() ? "unknown" : provider;
        message = message == null ? "" : message;
    }
}
