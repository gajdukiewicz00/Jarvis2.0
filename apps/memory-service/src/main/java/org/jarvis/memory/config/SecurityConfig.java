package org.jarvis.memory.config;

import org.jarvis.common.security.BaseSecurityConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

/**
 * Production security configuration for Memory Service.
 */
@Configuration
@EnableWebSecurity
@Profile("!dev")
@ConditionalOnWebApplication(type = Type.SERVLET)
public class SecurityConfig extends BaseSecurityConfig {
    @Override
    protected String[] getPublicEndpoints() {
        return new String[]{
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/memory/health"
        };
    }
}
