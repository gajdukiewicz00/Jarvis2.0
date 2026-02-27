package org.jarvis.common.logging;

import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Fails fast when potentially unsafe PII logging is enabled in production.
 */
public final class PiiLoggingGuard {

    private final Environment environment;
    private final boolean piiLoggingEnabled;

    public PiiLoggingGuard(Environment environment, boolean piiLoggingEnabled) {
        this.environment = environment;
        this.piiLoggingEnabled = piiLoggingEnabled;
    }

    public void validate() {
        if (isProductionLike() && piiLoggingEnabled) {
            throw new IllegalStateException(
                    "SECURITY_HARD_FAIL_PII_ENABLED: logging.pii.enabled=true is forbidden when profile 'prod' is active or ENV=prod");
        }
    }

    private boolean isProductionLike() {
        return hasProdProfile() || isProdEnv();
    }

    private boolean hasProdProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .map(String::trim)
                .anyMatch(profile -> "prod".equalsIgnoreCase(profile));
    }

    private boolean isProdEnv() {
        String envValue = environment.getProperty("ENV");
        return envValue != null && "prod".equalsIgnoreCase(envValue.trim());
    }
}
