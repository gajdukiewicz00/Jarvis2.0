package org.jarvis.vision.config;

import org.jarvis.common.security.BaseSecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

@Configuration
@EnableWebSecurity
@Profile("!dev")
public class SecurityConfig extends BaseSecurityConfig {

    @Override
    protected String[] getPublicEndpoints() {
        return new String[]{
                "/actuator/health",
                "/actuator/health/**",
                "/actuator/info",
                "/api/v1/vision/health"
        };
    }
}
