package org.jarvis.common;

import org.jarvis.common.filter.TracePropagationFilter;
import org.jarvis.common.security.ServiceJwtFilter;
import org.jarvis.common.security.ServiceJwtProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Auto-configuration that explicitly registers jarvis-common beans.
 *
 * <p>Each bean is guarded by {@code @ConditionalOnMissingBean} so services can
 * override with their own implementations.</p>
 *
 * <h3>Registered beans</h3>
 * <ul>
 *   <li>{@link TracePropagationFilter} — servlet filter at {@code HIGHEST_PRECEDENCE},
 *       puts {@code X-Trace-Id} header into MDC before any other filter logs</li>
 *   <li>{@link ServiceJwtProvider} — always created; when {@code service.jwt.secret}
 *       (or {@code jwt.secret}) is absent and {@code service.jwt.required=true} (default),
 *       fails fast at startup. With {@code required=false}, becomes disabled
 *       ({@code isEnabled()=false})</li>
 *   <li>{@link ServiceJwtFilter} — validates {@code X-Service-Token}; no-op when
 *       provider is disabled</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = Type.SERVLET)
public class JarvisCommonAutoConfiguration {

    // ── Observability ──────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public TracePropagationFilter tracePropagationFilter() {
        return new TracePropagationFilter();
    }

    @Bean
    @ConditionalOnMissingBean(name = "tracePropagationFilterRegistration")
    public FilterRegistrationBean<TracePropagationFilter> tracePropagationFilterRegistration(
            TracePropagationFilter filter) {
        FilterRegistrationBean<TracePropagationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    // ── Service-to-service JWT ─────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public ServiceJwtProvider serviceJwtProvider(
            @Value("${service.jwt.secret:}") String serviceSecret,
            @Value("${jwt.secret:}") String jwtSecret,
            @Value("${service.jwt.issuer:jarvis-internal}") String issuer,
            @Value("${service.jwt.audience:jarvis-services}") String audience,
            @Value("${service.jwt.ttl-seconds:300}") long ttlSeconds,
            @Value("${service.jwt.required:true}") boolean required) {
        return new ServiceJwtProvider(serviceSecret, jwtSecret, issuer, audience, ttlSeconds, required);
    }

    @Bean
    @ConditionalOnMissingBean
    public ServiceJwtFilter serviceJwtFilter(ServiceJwtProvider serviceJwtProvider) {
        return new ServiceJwtFilter(serviceJwtProvider);
    }

    @Bean
    @ConditionalOnMissingBean(name = "serviceJwtFilterRegistration")
    public FilterRegistrationBean<ServiceJwtFilter> serviceJwtFilterRegistration(ServiceJwtFilter filter) {
        FilterRegistrationBean<ServiceJwtFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
