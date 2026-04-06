package org.jarvis.analytics.config;

import org.jarvis.analytics.filter.TokenValidationFilter;
import org.jarvis.common.security.BaseSecurityConfig;
import org.jarvis.common.security.GatewayAuthFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

/**
 * Production security configuration for Analytics Service.
 */
@Configuration
@EnableWebSecurity
@Profile("!dev")
@ConditionalOnWebApplication(type = Type.SERVLET)
public class SecurityConfig extends BaseSecurityConfig {

    @Override
    protected void configureAdditionalSecurity(HttpSecurity http) throws Exception {
        http.addFilterAfter(new TokenValidationFilter(), GatewayAuthFilter.class);
    }
}
