package org.jarvis.common.logging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
public class PiiLoggingGuardAutoConfiguration {

    @Bean
    ApplicationRunner piiLoggingGuardRunner(
            Environment environment,
            @Value("${logging.pii.enabled:false}") boolean piiLoggingEnabled) {
        return args -> new PiiLoggingGuard(environment, piiLoggingEnabled).validate();
    }
}
