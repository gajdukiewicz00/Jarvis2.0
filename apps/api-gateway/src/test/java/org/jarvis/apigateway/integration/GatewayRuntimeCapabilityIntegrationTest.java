package org.jarvis.apigateway.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.apigateway.ApiGatewayApplication;
import org.jarvis.apigateway.websocket.PcControlWebSocketHandler;
import org.jarvis.apigateway.websocket.VoiceWebSocketProxyHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
        classes = ApiGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=",
                "JWT_SECRET=0123456789012345678901234567890123456789012345678901234567890123",
                "jarvis.jwt.secret=0123456789012345678901234567890123456789012345678901234567890123",
                "jwt.secret=0123456789012345678901234567890123456789012345678901234567890123",
                "jarvis.jwt.enabled=false",
                "jarvis.jwt.issuer=jarvis",
                "service.jwt.secret=service-secret-01234567890123456789012345678901",
                "services.memory.enabled=true",
                "services.llm.enabled=true",
                "services.vision-security.enabled=true",
                "services.pc-control.stub-mode=true",
                "jarvis.runtime.mode=k8s"
        })
@ActiveProfiles("dev")
class GatewayRuntimeCapabilityIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private VoiceWebSocketProxyHandler voiceWebSocketProxyHandler;

    @MockBean
    private PcControlWebSocketHandler pcControlWebSocketHandler;

    @Test
    void pcControlRoutesExposeUnsupportedRuntimeModeInsteadOfProxyingBlindly() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/v1/pc/sessions"), String.class);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        Map<String, Object> body = jsonBody(response);
        assertEquals("UNSUPPORTED_RUNTIME_MODE", body.get("error"));
        assertEquals("pc-control", body.get("capability"));
        assertEquals("pc-control", body.get("upstreamService"));
        assertEquals("k8s", body.get("runtimeMode"));
    }

    @Test
    void capabilitiesEndpointMarksRuntimeRestrictedRoutesExplicitly() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/v1/capabilities"), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = jsonBody(response);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> routes = (List<Map<String, Object>>) body.get("routes");

        assertEquals("k8s", body.get("runtimeMode"));
        assertEquals("degraded", body.get("status"));
        assertEquals("unsupported-runtime", findRoute(routes, "/api/v1/pc/**").get("status"));
        assertEquals("unsupported-runtime", findRoute(routes, "/api/v1/vision-security/**").get("status"));
    }

    private Map<String, Object> jsonBody(ResponseEntity<String> response) throws Exception {
        return objectMapper.readValue(response.getBody(), new TypeReference<>() {
        });
    }

    private Map<String, Object> findRoute(List<Map<String, Object>> routes, String route) {
        return routes.stream()
                .filter(entry -> route.equals(entry.get("route")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing route descriptor for " + route));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
