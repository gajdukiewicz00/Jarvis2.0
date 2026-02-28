package org.jarvis.apigateway.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Dev-only security configuration.
 * <p>
 * Provides a minimal {@link SecurityFilterChain} that permits all requests.
 * JWT filters are NOT added to the chain (they are disabled via
 * {@code jarvis.jwt.enabled=false} in application-dev.yaml anyway).
 * <p>
 * This replaces the previous implicit "no chain at all" behaviour
 * with an explicit, auditable permitAll() chain.
 */
@Slf4j
@Configuration
@Profile("dev")
public class DevSecurityConfig {

    @Bean
    public SecurityFilterChain devSecurityFilterChain(HttpSecurity http) throws Exception {
        log.info("DevSecurityConfig: configuring permitAll() security chain for dev profile");

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll());

        return http.build();
    }
}

