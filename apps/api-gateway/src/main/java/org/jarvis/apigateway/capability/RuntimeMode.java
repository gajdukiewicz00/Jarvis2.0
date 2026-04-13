package org.jarvis.apigateway.capability;

import java.util.Locale;

public enum RuntimeMode {
    LOCAL,
    DEV,
    K8S,
    UNKNOWN;

    public static RuntimeMode from(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "local" -> LOCAL;
            case "dev" -> DEV;
            case "k8s", "prod", "production", "cluster" -> K8S;
            default -> UNKNOWN;
        };
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
