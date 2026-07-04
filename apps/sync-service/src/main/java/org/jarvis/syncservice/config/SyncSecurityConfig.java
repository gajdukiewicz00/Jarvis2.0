package org.jarvis.syncservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for sync-service.
 *
 * <p>sync-service is the device-facing, local-first sync endpoint. A phone pairs
 * over an end-to-end encrypted handshake (Ed25519 signature + X25519 key exchange)
 * and every sync payload is ChaCha20-Poly1305 sealed with the per-device session
 * key. The transport itself is therefore intentionally unauthenticated at the HTTP
 * layer: the device holds no internal service JWT, and confidentiality + integrity
 * come from the E2E envelope, not from the channel.
 *
 * <p>Without this explicit chain, Spring Boot's default security auto-configuration
 * (pulled in transitively via jarvis-common) locks every endpoint behind HTTP Basic
 * with a generated password, which a paired device can never satisfy. We therefore
 * permit the device API and actuator and disable session / basic / form login.
 */
@Configuration
@EnableWebSecurity
public class SyncSecurityConfig {

    @Bean
    public SecurityFilterChain syncFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/sync/**", "/actuator/**").permitAll()
                        .anyRequest().permitAll());
        return http.build();
    }
}
