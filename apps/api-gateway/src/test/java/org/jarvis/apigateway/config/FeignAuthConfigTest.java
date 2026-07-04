package org.jarvis.apigateway.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.jarvis.common.security.GatewayAuthFilter;
import org.jarvis.common.security.ServiceJwtProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeignAuthConfigTest {

    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    private final FeignAuthConfig config = new FeignAuthConfig();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void serviceJwtProviderBeanBuildsUsableProvider() {
        ServiceJwtProvider provider = config.serviceJwtProvider(
                "a-service-secret-that-is-at-least-32-bytes-long", "", "jarvis-internal", "jarvis-services",
                300L, true, true);

        assertThat(provider.isEnabled()).isTrue();
        assertThat(provider.createToken("api-gateway", List.of("SVC_INTERNAL"))).isNotBlank();
    }

    @Test
    void interceptorAddsServiceTokenWhenMissing() {
        ServiceJwtProvider provider = enabledProvider();
        RequestInterceptor interceptor = config.serviceAuthInterceptor(provider, "api-gateway");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers()).containsKey(SERVICE_TOKEN_HEADER);
    }

    @Test
    void interceptorDoesNotOverwriteExistingServiceToken() {
        ServiceJwtProvider provider = enabledProvider();
        RequestInterceptor interceptor = config.serviceAuthInterceptor(provider, "api-gateway");
        RequestTemplate template = new RequestTemplate();
        template.header(SERVICE_TOKEN_HEADER, "preset-token");

        interceptor.apply(template);

        assertThat(template.headers().get(SERVICE_TOKEN_HEADER)).containsExactly("preset-token");
    }

    @Test
    void interceptorSkipsUserHeadersWhenNoAuthenticationPresent() {
        SecurityContextHolder.clearContext();
        ServiceJwtProvider provider = enabledProvider();
        RequestInterceptor interceptor = config.serviceAuthInterceptor(provider, "api-gateway");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers()).doesNotContainKey(GatewayAuthFilter.USER_ID_HEADER);
    }

    @Test
    void interceptorSkipsUserHeadersWhenAuthenticationIsUnauthenticated() {
        Authentication unauthenticated = mock(Authentication.class);
        when(unauthenticated.isAuthenticated()).thenReturn(false);
        SecurityContextHolder.getContext().setAuthentication(unauthenticated);
        ServiceJwtProvider provider = enabledProvider();
        RequestInterceptor interceptor = config.serviceAuthInterceptor(provider, "api-gateway");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers()).doesNotContainKey(GatewayAuthFilter.USER_ID_HEADER);
    }

    @Test
    void interceptorPropagatesRealUserHeadersExcludingSvcInternalAuthority() {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "user-42", null, List.of(
                        new SimpleGrantedAuthority("USER"),
                        new SimpleGrantedAuthority("SVC_INTERNAL"),
                        (org.springframework.security.core.GrantedAuthority) () -> ""));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        ServiceJwtProvider provider = enabledProvider();
        RequestInterceptor interceptor = config.serviceAuthInterceptor(provider, "api-gateway");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        // SVC_INTERNAL authority present with no "delegated-by:" detail -> propagation suppressed.
        assertThat(template.headers()).doesNotContainKey(GatewayAuthFilter.USER_ID_HEADER);
        assertThat(template.headers()).doesNotContainKey(GatewayAuthFilter.USER_ROLES_HEADER);
    }

    @Test
    void interceptorPropagatesDelegatedUserWhenDetailsMarksDelegation() {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "user-42", null, List.of(
                        new SimpleGrantedAuthority("USER"),
                        new SimpleGrantedAuthority("SVC_INTERNAL")));
        authentication.setDetails("delegated-by:desktop-agent");
        SecurityContextHolder.getContext().setAuthentication(authentication);
        ServiceJwtProvider provider = enabledProvider();
        RequestInterceptor interceptor = config.serviceAuthInterceptor(provider, "api-gateway");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers().get(GatewayAuthFilter.USER_ID_HEADER)).containsExactly("user-42");
        assertThat(template.headers().get(GatewayAuthFilter.USER_ROLES_HEADER)).containsExactly("USER");
    }

    @Test
    void interceptorPropagatesPlainUserWithoutSvcInternalAuthority() {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "user-7", null, List.of(new SimpleGrantedAuthority("USER"), new SimpleGrantedAuthority("ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        ServiceJwtProvider provider = enabledProvider();
        RequestInterceptor interceptor = config.serviceAuthInterceptor(provider, "api-gateway");
        RequestTemplate template = new RequestTemplate();
        template.header(GatewayAuthFilter.USER_ID_HEADER, "preset-user");

        interceptor.apply(template);

        // Already-present user id header is left untouched, but roles are still filled in.
        assertThat(template.headers().get(GatewayAuthFilter.USER_ID_HEADER)).containsExactly("preset-user");
        assertThat(template.headers().get(GatewayAuthFilter.USER_ROLES_HEADER)).containsExactly("USER,ADMIN");
    }

    private ServiceJwtProvider enabledProvider() {
        return new ServiceJwtProvider(
                "a-service-secret-that-is-at-least-32-bytes-long", "", "jarvis-internal", "jarvis-services",
                300L, true, true);
    }
}
