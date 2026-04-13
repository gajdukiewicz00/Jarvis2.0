package org.jarvis.planner.config;

import org.jarvis.common.security.BaseSecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

/**
 * Production security configuration for Planner Service.
 * Inherits service-JWT + gateway-delegation filter chain from BaseSecurityConfig.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("!dev")
public class SecurityConfig extends BaseSecurityConfig {
}
