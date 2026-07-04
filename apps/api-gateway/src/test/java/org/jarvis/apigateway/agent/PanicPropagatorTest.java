package org.jarvis.apigateway.agent;

import org.jarvis.common.security.ServiceJwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PanicPropagatorTest {

    @Mock
    private ServiceJwtProvider serviceJwtProvider;

    private RestTemplate restTemplate;
    private PanicPropagator propagator;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.setConnectTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.setReadTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.build()).thenReturn(restTemplate);
        when(serviceJwtProvider.createToken(anyString(), any())).thenReturn("svc-token");

        propagator = new PanicPropagator(builder, serviceJwtProvider, "http://orchestrator:8083");
    }

    @SuppressWarnings("unchecked")
    @Test
    void propagateEngagedPostsToOrchestratorControlEndpoint() {
        propagator.propagate(true, "operator", "drill");

        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(
                eq("http://orchestrator:8083/internal/control/panic"), captor.capture(), eq(Void.class));

        HttpEntity<Map<String, Object>> entity = captor.getValue();
        assertThat(entity.getHeaders().getFirst("X-Service-Token")).isEqualTo("svc-token");
        assertThat(entity.getBody())
                .containsEntry("engaged", true)
                .containsEntry("actor", "operator")
                .containsEntry("reason", "drill");
        verify(serviceJwtProvider).createToken("api-gateway", List.of("SVC_INTERNAL"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void propagateDefaultsNullActorAndReason() {
        propagator.propagate(false, null, null);

        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(anyString(), captor.capture(), eq(Void.class));

        Map<String, Object> body = captor.getValue().getBody();
        assertThat(body).containsEntry("engaged", false).containsEntry("actor", "api").containsEntry("reason", "");
    }

    @Test
    void propagateSwallowsRestClientExceptionsWithoutThrowing() {
        when(restTemplate.postForEntity(anyString(), any(), eq(Void.class)))
                .thenThrow(new RestClientException("connection refused"));

        assertThatCode(() -> propagator.propagate(true, "operator", "drill")).doesNotThrowAnyException();
    }
}
