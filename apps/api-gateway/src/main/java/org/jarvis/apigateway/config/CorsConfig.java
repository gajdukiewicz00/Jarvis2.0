package org.jarvis.apigateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS Configuration for API Gateway.
 * 
 * Конфигурация с default values для всех параметров, чтобы сервис
 * мог стартовать даже если cors.* не определены в application.yml
 */
@Slf4j
@Configuration
public class CorsConfig {

    @Value("${cors.enabled:true}")
    private boolean corsEnabled;

    // Default: разрешаем localhost и локальную сеть
    @Value("#{'${cors.allowed-origins:http://localhost:*,http://127.0.0.1:*}'.split(',')}")
    private List<String> allowedOrigins;

    // Default: стандартные HTTP методы
    @Value("#{'${cors.allowed-methods:GET,POST,PUT,DELETE,PATCH,OPTIONS}'.split(',')}")
    private List<String> allowedMethods;

    // Default: все заголовки
    @Value("#{'${cors.allowed-headers:*}'.split(',')}")
    private List<String> allowedHeaders;

    @Value("${cors.allow-credentials:true}")
    private boolean allowCredentials;

    @Bean
    public CorsFilter corsFilter() {
        if (!corsEnabled) {
            log.info("CORS is disabled");
            return new CorsFilter(new UrlBasedCorsConfigurationSource());
        }

        log.info("CORS is enabled with origins: {}", allowedOrigins);

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(allowedOrigins);
        config.setAllowedMethods(allowedMethods);
        config.setAllowedHeaders(allowedHeaders);
        config.setAllowCredentials(allowCredentials);
        config.setMaxAge(3600L); // 1 hour

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}
