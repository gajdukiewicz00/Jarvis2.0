package org.jarvis.apigateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.stream.Collectors;

/**
 * CORS Configuration for API Gateway.
 * 
 * Конфигурация с default values для всех параметров, чтобы сервис
 * мог стартовать даже если cors.* не определены в application.yml
 */
@Slf4j
@Configuration
public class CorsConfig {

    @Value("${cors.enabled:false}")
    private boolean corsEnabled;

    @Value("#{'${cors.allowed-origins:}'.split(',')}")
    private List<String> allowedOrigins;

    // Default: стандартные HTTP методы
    @Value("#{'${cors.allowed-methods:GET,POST,PUT,DELETE,PATCH,OPTIONS}'.split(',')}")
    private List<String> allowedMethods;

    // Default: все заголовки
    @Value("#{'${cors.allowed-headers:*}'.split(',')}")
    private List<String> allowedHeaders;

    @Value("${cors.allow-credentials:false}")
    private boolean allowCredentials;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        if (!corsEnabled) {
            log.info("CORS is disabled");
            return new UrlBasedCorsConfigurationSource();
        }

        List<String> normalizedOrigins = allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toList());

        if (normalizedOrigins.isEmpty()) {
            log.warn("CORS enabled but no allowed origins configured");
            return new UrlBasedCorsConfigurationSource();
        }

        if (allowCredentials && normalizedOrigins.stream().anyMatch(origin -> "*".equals(origin))) {
            throw new IllegalStateException("CORS misconfiguration: allowCredentials=true with wildcard origins");
        }

        log.info("CORS is enabled with origins: {}", normalizedOrigins);

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(normalizedOrigins);
        config.setAllowedMethods(allowedMethods);
        config.setAllowedHeaders(allowedHeaders);
        config.setAllowCredentials(allowCredentials);
        config.setMaxAge(3600L); // 1 hour

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return source;
    }
}
