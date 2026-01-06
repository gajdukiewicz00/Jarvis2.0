package org.jarvis.apigateway.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.filter.JwtFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Security configuration for API Gateway (production mode).
 * Configures JWT authentication and public/protected endpoints.
 */
@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@Profile("!dev")
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtFilter jwtFilter;

    /**
     * Ignore security filter chain entirely for internal, actuator and websocket paths.
     * This makes sure the handshake for PC Control is not intercepted by auth filters.
     */
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return web -> web.ignoring().requestMatchers(
                "/ws/**",
                "/internal/**",
                "/actuator/**",
                "/health",
                "/favicon.ico");
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        log.info("🔧 SecurityConfig: Configuring SecurityFilterChain...");
        log.info("🔧 SecurityConfig: JwtFilter bean: {}", jwtFilter != null ? "FOUND" : "NULL");
        log.info("🔧 SecurityConfig: JwtAuthenticationFilter bean: {}",
                jwtAuthenticationFilter != null ? "FOUND" : "NULL");

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    log.info("🔧 SecurityConfig: Configuring authorization rules...");
                    auth
                            // Public endpoints - no authentication required
                            .requestMatchers("/auth/**").permitAll() // Login, register, refresh - NO AUTH REQUIRED
                            .requestMatchers("/api/v1/**").permitAll() // Allow all /api/v1/** endpoints (temporary for
                                                                       // development)
                            .requestMatchers("/ws/**").permitAll() // WebSocket endpoints
                            .requestMatchers("/internal/**").permitAll() // Internal service-to-service endpoints
                            .requestMatchers("/actuator/**").permitAll() // Health checks
                            .requestMatchers("/public/**").permitAll() // Public resources
                            // All other endpoints require authentication
                            .anyRequest().authenticated();
                    log.info("🔧 SecurityConfig: Authorization rules configured - /auth/** is permitAll()");
                })
                // IMPORTANT: Filters are added AFTER authorizeHttpRequests
                // JwtFilter checks whitelist internally and should allow /auth/** requests
                .addFilterBefore(jwtFilter,
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        log.info("🔧 SecurityConfig: SecurityFilterChain configured successfully");
        return http.build();
    }

    /**
     * Open CORS configuration for PC control REST + WebSocket handshake.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.addAllowedOriginPattern("*");
        configuration.addAllowedHeader("*");
        configuration.addAllowedMethod("*");
        configuration.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
