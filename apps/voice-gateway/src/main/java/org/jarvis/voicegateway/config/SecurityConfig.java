package org.jarvis.voicegateway.config;

import org.jarvis.common.security.BaseSecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

/**
 * Production security configuration for Voice Gateway.
 */
@Configuration
@EnableWebSecurity
@Profile("!dev")
public class SecurityConfig extends BaseSecurityConfig {
    
    @Override
    protected String[] getPublicEndpoints() {
        return new String[]{
            "/actuator/**",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/ws/**"  // WebSocket endpoints
        };
    }
}
