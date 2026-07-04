package org.jarvis.media.config;

import org.jarvis.common.security.BaseSecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

/**
 * Production security configuration for the Media Service.
 *
 * <p>Inherits the service-JWT + gateway-delegation filter chain from
 * {@link BaseSecurityConfig}: {@code /actuator/health|info|prometheus} is public,
 * every other endpoint (including all media job endpoints) requires authentication.
 * No auth weakening — media is an internal service reached through the gateway or
 * by another service presenting a service token.</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("!dev")
public class SecurityConfig extends BaseSecurityConfig {
}
