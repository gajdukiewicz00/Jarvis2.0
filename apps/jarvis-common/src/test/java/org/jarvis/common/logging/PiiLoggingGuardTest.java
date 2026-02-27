package org.jarvis.common.logging;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PiiLoggingGuardTest {

    @Test
    void prodProfileAndPiiEnabledFailsFast() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("prod");

        PiiLoggingGuard guard = new PiiLoggingGuard(environment, true);

        IllegalStateException error = assertThrows(IllegalStateException.class, guard::validate);
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("SECURITY_HARD_FAIL_PII_ENABLED"));
    }

    @Test
    void prodProfileAndPiiDisabledIsAllowed() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("prod");

        PiiLoggingGuard guard = new PiiLoggingGuard(environment, false);

        assertDoesNotThrow(guard::validate);
    }

    @Test
    void nonProdProfileAndPiiEnabledIsAllowed() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("dev");

        PiiLoggingGuard guard = new PiiLoggingGuard(environment, true);

        assertDoesNotThrow(guard::validate);
    }

    @Test
    void prodEnvVariableAndPiiEnabledFailsFast() {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("test-env", Map.of("ENV", "prod"))
        );

        PiiLoggingGuard guard = new PiiLoggingGuard(environment, true);

        IllegalStateException error = assertThrows(IllegalStateException.class, guard::validate);
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("SECURITY_HARD_FAIL_PII_ENABLED"));
    }
}
