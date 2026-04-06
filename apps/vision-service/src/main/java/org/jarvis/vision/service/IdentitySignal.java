package org.jarvis.vision.service;

import java.util.Map;

public record IdentitySignal(
        IdentitySignalState state,
        double confidence,
        String message,
        Map<String, String> diagnostics) {

    public IdentitySignal {
        state = state == null ? IdentitySignalState.UNAVAILABLE : state;
        message = message == null ? "" : message;
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
