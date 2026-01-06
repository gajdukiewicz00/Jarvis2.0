package org.jarvis.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Development security configuration that permits all requests.
 * 
 * Usage:
 * <pre>
 * @Configuration
 * @EnableWebSecurity
 * @Profile("dev")
 * public class DevSecurityConfig extends BaseDevSecurityConfig {
 *     // Optionally override
 * }
 * </pre>
 */
@Slf4j
public abstract class DevSecurityConfig {

    private final String serviceName;

    protected DevSecurityConfig(String serviceName) {
        this.serviceName = serviceName;
    }

    @Bean
    public SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        log.info("🔓 {} DevSecurityConfig: Loading DEVELOPMENT security (permitAll)", serviceName);
        
        http
            .csrf(csrf -> csrf.disable())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll()
            )
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        log.info("🔓 {} DevSecurityConfig: All requests permitted in dev mode", serviceName);
        return http.build();
    }
}

