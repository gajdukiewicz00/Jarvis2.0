package org.jarvis.common.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.jarvis.common.security.ServiceJwtProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ServiceFeignAutoConfigurationTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void interceptorAddsServiceTokenAndFiltersInternalAuthorityFromDelegatedUserRoles() {
        ServiceJwtProvider serviceJwtProvider = new ServiceJwtProvider(
                "0123456789abcdef0123456789abcdef",
                "",
                "jarvis-internal",
                "jarvis-services",
                300,
                true);

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "user-123",
                null,
                List.of(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("SVC_INTERNAL")));
        authentication.setDetails("delegated-by:api-gateway");
        SecurityContextHolder.getContext().setAuthentication(authentication);

        RequestInterceptor interceptor = new ServiceFeignAutoConfiguration()
                .serviceAuthInterceptor(serviceJwtProvider, "analytics-service");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertNotNull(template.headers().get("X-Service-Token"));
        assertFalse(template.headers().get("X-Service-Token").isEmpty());
        assertEquals("user-123", firstHeader(template, "X-User-Id"));
        assertEquals("ROLE_USER", firstHeader(template, "X-User-Roles"));
    }

    @Test
    void interceptorDoesNotInventDelegatedUserHeadersForServiceOnlyAuthentication() {
        ServiceJwtProvider serviceJwtProvider = new ServiceJwtProvider(
                "0123456789abcdef0123456789abcdef",
                "",
                "jarvis-internal",
                "jarvis-services",
                300,
                true);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "analytics-service",
                null,
                List.of(new SimpleGrantedAuthority("SVC_INTERNAL"))));

        RequestInterceptor interceptor = new ServiceFeignAutoConfiguration()
                .serviceAuthInterceptor(serviceJwtProvider, "analytics-service");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertNotNull(template.headers().get("X-Service-Token"));
        assertFalse(template.headers().get("X-Service-Token").isEmpty());
        assertFalse(template.headers().containsKey("X-User-Id"));
        assertFalse(template.headers().containsKey("X-User-Roles"));
    }

    private String firstHeader(RequestTemplate template, String headerName) {
        return template.headers().get(headerName).iterator().next();
    }
}
