package org.jarvis.llm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration
 * 
 * Note: In production, this service should be behind api-gateway
 * and trust the gateway's authentication
 */
@Configuration
@EnableWebSecurity
@Profile("!dev")
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/health", "/ws/**").permitAll()
                .anyRequest().permitAll()  // Trust api-gateway for auth
            );
        
        return http.build();
    }
}
