package org.jarvis.orchestrator.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.jarvis.common.JarvisHttpHeaders;
import org.jarvis.common.security.ServiceJwtFilter;
import org.jarvis.common.security.ServiceJwtProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceAuthFeignConfigTest {

    private final ServiceAuthFeignConfig config = new ServiceAuthFeignConfig();
    private final ServiceJwtProvider jwtProvider = mock(ServiceJwtProvider.class);

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void addsServiceTokenHeaderUsingProvidedServiceName() {
        RequestContextHolder.resetRequestAttributes();
        when(jwtProvider.createToken(eq("orchestrator"), eq(List.of("SVC_INTERNAL"))))
                .thenReturn("svc-token-abc");
        RequestInterceptor interceptor = config.serviceAuthInterceptor(jwtProvider, "orchestrator");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers().get(ServiceJwtFilter.SERVICE_TOKEN_HEADER))
                .containsExactly("svc-token-abc");
        verify(jwtProvider).createToken("orchestrator", List.of("SVC_INTERNAL"));
    }

    @Test
    void noPropagationWhenNoServletRequestContextPresent() {
        RequestContextHolder.resetRequestAttributes();
        when(jwtProvider.createToken(eq("orchestrator"), eq(List.of("SVC_INTERNAL"))))
                .thenReturn("tok");
        RequestInterceptor interceptor = config.serviceAuthInterceptor(jwtProvider, "orchestrator");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertThat(template.headers()).doesNotContainKey(JarvisHttpHeaders.MODEL_PROFILE);
        assertThat(template.headers()).doesNotContainKey(JarvisHttpHeaders.CORRELATION_ID);
        assertThat(template.headers()).doesNotContainKey(JarvisHttpHeaders.USER_ID);
    }

    @Test
    void propagatesPresentHeadersFromInboundServletRequest() {
        when(jwtProvider.createToken(eq("orchestrator"), eq(List.of("SVC_INTERNAL"))))
                .thenReturn("tok");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(JarvisHttpHeaders.MODEL_PROFILE, "voice-fast");
        request.addHeader(JarvisHttpHeaders.CORRELATION_ID, "corr-77");
        request.addHeader(JarvisHttpHeaders.USER_ID, "user-77");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        RequestInterceptor interceptor = config.serviceAuthInterceptor(jwtProvider, "orchestrator");
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        assertThat(template.headers().get(JarvisHttpHeaders.MODEL_PROFILE)).containsExactly("voice-fast");
        assertThat(template.headers().get(JarvisHttpHeaders.CORRELATION_ID)).containsExactly("corr-77");
        assertThat(template.headers().get(JarvisHttpHeaders.USER_ID)).containsExactly("user-77");
    }

    @Test
    void blankOrMissingInboundHeadersAreNotPropagated() {
        when(jwtProvider.createToken(eq("orchestrator"), eq(List.of("SVC_INTERNAL"))))
                .thenReturn("tok");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(JarvisHttpHeaders.MODEL_PROFILE, "   ");
        // CORRELATION_ID / USER_ID intentionally absent
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        RequestInterceptor interceptor = config.serviceAuthInterceptor(jwtProvider, "orchestrator");
        RequestTemplate template = new RequestTemplate();
        interceptor.apply(template);

        assertThat(template.headers()).doesNotContainKey(JarvisHttpHeaders.MODEL_PROFILE);
        assertThat(template.headers()).doesNotContainKey(JarvisHttpHeaders.CORRELATION_ID);
        assertThat(template.headers()).doesNotContainKey(JarvisHttpHeaders.USER_ID);
    }
}
