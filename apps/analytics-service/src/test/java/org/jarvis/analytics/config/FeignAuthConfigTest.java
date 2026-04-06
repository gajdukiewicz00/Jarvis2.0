package org.jarvis.analytics.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeignAuthConfigTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void interceptorPropagatesBearerTokenAndDelegatedRolesWithoutLeakingAuthDetails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer svc-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "user-123",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("SVC_INTERNAL")));
        authentication.setDetails("delegated-by:api-gateway");
        SecurityContextHolder.getContext().setAuthentication(authentication);

        RequestInterceptor interceptor = new FeignAuthConfig().authFeignInterceptor();
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers().get("Authorization")).containsExactly("Bearer svc-token");
        assertThat(template.headers().get("X-User-Id")).containsExactly("user-123");
        assertThat(template.headers().get("X-User-Roles")).containsExactly("ROLE_USER");
    }
}
