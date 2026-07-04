package org.jarvis.common.safety;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the shared safety beans ({@link SystemPanicState} global panic
 * state and {@link ToolPermissionPolicy}) so every service that depends on
 * jarvis-common reuses the SAME enforcement primitives at every entry point
 * (gateway executor, orchestrator publisher, voice fast-path).
 */
@AutoConfiguration
public class SafetyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SystemPanicState systemPanicState() {
        return new SystemPanicState();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolPermissionPolicy toolPermissionPolicy(
            @Value("${jarvis.tools.granted-permissions:}") String grantedCsv) {
        return new ToolPermissionPolicy(grantedCsv);
    }
}
