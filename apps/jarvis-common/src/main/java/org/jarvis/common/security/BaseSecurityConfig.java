package org.jarvis.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Base security configuration for internal microservices.
 * 
 * Usage:
 * <pre>
 * @Configuration
 * @EnableWebSecurity
 * @Profile("!dev")
 * public class SecurityConfig extends BaseSecurityConfig {
 *     // Optionally override methods
 * }
 * </pre>
 */
public abstract class BaseSecurityConfig {

    /**
     * Creates the security filter chain with gateway authentication.
     * Override this method to customize security rules.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ServiceJwtFilter serviceJwtFilter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(getPublicEndpoints()).permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(serviceJwtFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(createGatewayAuthFilter(), ServiceJwtFilter.class);
        
        configureAdditionalSecurity(http);
        
        return http.build();
    }

    /**
     * Returns array of public endpoints that don't require authentication.
     * Override to add service-specific public endpoints.
     */
    protected String[] getPublicEndpoints() {
        return new String[]{
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info"
        };
    }

    /**
     * Creates the gateway authentication filter.
     * Override to provide a custom implementation.
     */
    protected GatewayAuthFilter createGatewayAuthFilter() {
        return new GatewayAuthFilter();
    }

    /**
     * Hook for additional security configuration.
     * Override to add CORS, custom filters, etc.
     */
    protected void configureAdditionalSecurity(HttpSecurity http) throws Exception {
        // Default: no additional configuration
    }
}
