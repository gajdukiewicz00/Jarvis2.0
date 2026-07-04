package org.jarvis.apigateway.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.apigateway.ApiGatewayApplication;
import org.jarvis.apigateway.support.RecordingHttpServer;
import org.jarvis.apigateway.websocket.PcControlWebSocketHandler;
import org.jarvis.apigateway.websocket.VoiceWebSocketProxyHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                "services.llm.enabled=true",
                "services.memory.enabled=false",
                "services.vision-security.enabled=false",
                "jarvis.runtime.mode=k8s"
        })
@ActiveProfiles("dev")
class GatewayLlmIngressIntegrationTest {

    private static final RecordingHttpServer LLM_SERVER = RecordingHttpServer.start();

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

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("services.llm.url", LLM_SERVER::baseUrl);
    }

    @BeforeEach
    void resetServer() {
        LLM_SERVER.reset();
    }

    @AfterAll
    static void shutdownServer() {
        LLM_SERVER.close();
    }

    @Test
    void llmRuntimeRouteProxiesToEnabledLlmService() {
        LLM_SERVER.setHandler(request -> RecordingHttpServer.StubResponse.json(
                200,
                "{\"service\":\"llm-service\",\"status\":\"llm-only\"}"
        ));

        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/v1/llm/runtime"), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"service\":\"llm-service\",\"status\":\"llm-only\"}", response.getBody());

        RecordingHttpServer.RecordedRequest request = LLM_SERVER.lastRequest();
        assertNotNull(request);
        assertEquals("GET", request.method());
        assertEquals("/api/v1/llm/runtime", request.path());
        assertNotNull(request.header("X-Service-Token"));
        assertTrue(!request.header("X-Service-Token").isBlank());
    }

    @Test
    void capabilitiesEndpointMarksLlmRouteAvailableWhenEnabled() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/v1/capabilities"), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = objectMapper.readValue(response.getBody(), new TypeReference<>() {
        });
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> routes = (List<Map<String, Object>>) body.get("routes");

        assertEquals("k8s", body.get("runtimeMode"));
        assertEquals("available", findRoute(routes, "/api/v1/llm/**").get("status"));
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
