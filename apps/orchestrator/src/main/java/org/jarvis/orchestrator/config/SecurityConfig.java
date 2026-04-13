package org.jarvis.orchestrator.config;

import org.jarvis.common.security.BaseSecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

/**
 * Production security configuration for Orchestrator.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("!dev")
public class SecurityConfig extends BaseSecurityConfig {
}
