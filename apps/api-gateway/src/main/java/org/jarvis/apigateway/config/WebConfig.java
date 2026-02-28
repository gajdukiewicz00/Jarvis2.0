package org.jarvis.apigateway.config;

import lombok.RequiredArgsConstructor;
import org.jarvis.apigateway.filter.RequestLoggingFilter;
import org.jarvis.apigateway.interceptor.RateLimitInterceptor;
import org.jarvis.apigateway.security.JwtAuthFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**");
    }

    // ──────────────────────────────────────────────────────────────────
    // Prevent double servlet registration for JwtAuthFilter — it runs
    // ONLY inside springSecurityFilterChain (added by SecurityConfig).
    // Without this, @Component filters are auto-registered as servlet
    // filters AND run inside springSecurityFilterChain — executing twice.
    // ──────────────────────────────────────────────────────────────────

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtAuthFilter filter) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RequestLoggingFilter> loggingFilterRegistration(RequestLoggingFilter loggingFilter) {
        FilterRegistrationBean<RequestLoggingFilter> registration = new FilterRegistrationBean<>(loggingFilter);
        registration.setOrder(2);
        return registration;
    }
}
