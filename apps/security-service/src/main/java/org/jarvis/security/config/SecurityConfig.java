package org.jarvis.security.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Slf4j
@Configuration
@EnableWebSecurity
@Profile("!dev")
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        log.info("🔧 SecurityService SecurityConfig: Configuring security filter chain...");
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                log.info("🔧 SecurityService: Configuring public endpoints...");
                auth
                    .requestMatchers("/auth/**").permitAll() // Login, register, refresh - PUBLIC
                    .requestMatchers("/api/v1/security/**").permitAll()
                    .requestMatchers("/actuator/**").permitAll() // Health checks
                    .anyRequest().authenticated();
                log.info("🔧 SecurityService: Public endpoints configured - /auth/** is permitAll()");
            });
        log.info("🔧 SecurityService: SecurityFilterChain configured successfully");
        return http.build();
    }
}
