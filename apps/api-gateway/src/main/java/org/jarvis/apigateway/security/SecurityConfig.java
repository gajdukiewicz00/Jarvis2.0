package org.jarvis.apigateway.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.filter.JwtFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security configuration for API Gateway (production mode).
 * Configures JWT authentication and public/protected endpoints.
 */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@Profile("!dev")
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtFilter jwtFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        log.info("🔧 SecurityConfig: Configuring SecurityFilterChain...");
        log.info("🔧 SecurityConfig: JwtFilter bean: {}", jwtFilter != null ? "FOUND" : "NULL");
        log.info("🔧 SecurityConfig: JwtAuthenticationFilter bean: {}",
                jwtAuthenticationFilter != null ? "FOUND" : "NULL");

        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    log.info("🔧 SecurityConfig: Configuring authorization rules...");
                    auth
                            .requestMatchers("/actuator/health").permitAll()
                            // All other endpoints require authentication
                            .anyRequest().authenticated();
                    log.info("🔧 SecurityConfig: Authorization rules configured - /actuator/health is permitAll()");
                })
                // IMPORTANT: Filters are added AFTER authorizeHttpRequests
                .addFilterBefore(jwtFilter,
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        log.info("🔧 SecurityConfig: SecurityFilterChain configured successfully");
        return http.build();
    }

}
