package org.jarvis.voicegateway.config;

import org.jarvis.voicegateway.health.VoiceReadinessService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VoiceReadinessResponseBodyAdviceTest {

    @Test
    void readinessResponseIsFlattenedToStatusAndComponentsOnly() {
        VoiceReadinessService voiceReadinessService = mock(VoiceReadinessService.class);
        when(voiceReadinessService.currentSnapshot()).thenReturn(new VoiceReadinessService.Snapshot(
                "DEGRADED",
                Map.of(
                        "stt", "UP",
                        "tts", "DOWN",
                        "assets", "UP",
                        "orchestrator", "DOWN",
                        "websocket", "UP"),
                Map.of(),
                new VoiceReadinessService.DownstreamRouteSnapshot("DOWN", "API_GATEWAY_UNREACHABLE", "down", Map.of())));
        VoiceReadinessResponseBodyAdvice advice = new VoiceReadinessResponseBodyAdvice(voiceReadinessService);
        ReflectionTestUtils.setField(advice, "actuatorBasePath", "/actuator");

        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("http://127.0.0.1:18081/actuator/health/readiness"));

        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        Object transformed = advice.beforeBodyWrite(
                Health.down().build(),
                null,
                null,
                null,
                request,
                new ServletServerHttpResponse(servletResponse));

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) transformed;
        @SuppressWarnings("unchecked")
        Map<String, String> readinessComponents = (Map<String, String>) payload.get("components");

        assertEquals(200, servletResponse.getStatus());
        assertEquals("DEGRADED", payload.get("status"));
        assertEquals(Map.of(
                "stt", "UP",
                "tts", "DOWN",
                "assets", "UP",
                "orchestrator", "DOWN",
                "websocket", "UP"), readinessComponents);
    }

    @Test
    void nonReadinessResponsesPassThroughUntouched() {
        VoiceReadinessResponseBodyAdvice advice = new VoiceReadinessResponseBodyAdvice(mock(VoiceReadinessService.class));
        ReflectionTestUtils.setField(advice, "actuatorBasePath", "/actuator");

        Health body = Health.up().build();
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("http://127.0.0.1:18081/actuator/health"));

        Object transformed = advice.beforeBodyWrite(
                body,
                null,
                null,
                null,
                request,
                new ServletServerHttpResponse(new MockHttpServletResponse()));

        assertEquals(body, transformed);
    }
}
