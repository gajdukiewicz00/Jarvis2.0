package org.jarvis.apigateway.config;

import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeignConfigTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldNotApplyDevFallbackOutsideDevProfile() {
        Environment environment = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        FeignConfig config = new FeignConfig(environment, true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        RequestTemplate template = new RequestTemplate();
        config.requestInterceptor().apply(template);

        assertFalse(template.headers().containsKey("X-User-Id"));
        assertFalse(template.headers().containsKey("X-Username"));
        assertFalse(template.headers().containsKey("X-User-Role"));
    }

    @Test
    void shouldApplyDevFallbackOnlyWithFlagInDevProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        FeignConfig config = new FeignConfig(environment, true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        RequestTemplate template = new RequestTemplate();
        config.requestInterceptor().apply(template);

        assertTrue(template.headers().containsKey("X-User-Id"));
        assertTrue(template.headers().containsKey("X-Username"));
        assertTrue(template.headers().containsKey("X-User-Role"));
        assertEquals("dev-user", firstValue(template.headers().get("X-User-Id")));
        assertEquals("dev-user", firstValue(template.headers().get("X-Username")));
        assertEquals("USER", firstValue(template.headers().get("X-User-Role")));
    }

    @Test
    void shouldPropagateRealUserHeadersWithoutFallbackOverride() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        FeignConfig config = new FeignConfig(environment, true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "real-user");
        request.addHeader("X-Username", "real-user-name");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        RequestTemplate template = new RequestTemplate();
        config.requestInterceptor().apply(template);

        assertEquals("real-user", firstValue(template.headers().get("X-User-Id")));
        assertEquals("real-user-name", firstValue(template.headers().get("X-Username")));
    }

    private String firstValue(Collection<String> values) {
        return values == null || values.isEmpty() ? null : values.iterator().next();
    }
}
