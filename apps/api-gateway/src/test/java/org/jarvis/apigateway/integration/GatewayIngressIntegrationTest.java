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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                "services.memory.enabled=false",
                "services.llm.enabled=false",
                "services.vision-security.enabled=false",
                "jarvis.runtime.mode=local"
        })
@ActiveProfiles("dev")
class GatewayIngressIntegrationTest {

    private static final RecordingHttpServer ANALYTICS_SERVER = RecordingHttpServer.start();
    private static final RecordingHttpServer SECURITY_SERVER = RecordingHttpServer.start();
    private static final RecordingHttpServer PLANNER_SERVER = RecordingHttpServer.start();
    private static final RecordingHttpServer LIFE_TRACKER_SERVER = RecordingHttpServer.start();

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
        registry.add("services.analytics.url", ANALYTICS_SERVER::baseUrl);
        registry.add("services.security.url", SECURITY_SERVER::baseUrl);
        registry.add("services.planner.url", PLANNER_SERVER::baseUrl);
        registry.add("services.life-tracker.url", LIFE_TRACKER_SERVER::baseUrl);
        registry.add("services.memory.url", () -> "http://127.0.0.1:65530");
        registry.add("services.llm.url", () -> "http://127.0.0.1:65531");
        registry.add("services.vision-security.url", () -> "http://127.0.0.1:65532");
    }

    @BeforeEach
    void resetServers() {
        ANALYTICS_SERVER.reset();
        SECURITY_SERVER.reset();
        PLANNER_SERVER.reset();
        LIFE_TRACKER_SERVER.reset();
    }

    @AfterAll
    static void shutdownServers() {
        ANALYTICS_SERVER.close();
        SECURITY_SERVER.close();
        PLANNER_SERVER.close();
        LIFE_TRACKER_SERVER.close();
    }

    @Test
    void analyticsProxyPropagatesHeadersBodyAndQuery() {
        ANALYTICS_SERVER.setHandler(request -> RecordingHttpServer.StubResponse.json(200, "{\"service\":\"analytics\"}"));

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "user-7");
        headers.add("X-Correlation-ID", "corr-analytics-1");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/v1/analytics/report/monthly?from=2026-03-01&to=2026-03-31"),
                HttpMethod.POST,
                new HttpEntity<>("{\"view\":\"summary\"}", headers),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"service\":\"analytics\"}", response.getBody());

        RecordingHttpServer.RecordedRequest request = ANALYTICS_SERVER.lastRequest();
        assertNotNull(request);
        assertEquals("POST", request.method());
        assertEquals("/api/v1/analytics/report/monthly", request.path());
        assertEquals("from=2026-03-01&to=2026-03-31", request.query());
        assertEquals("{\"view\":\"summary\"}", request.bodyAsString());
        assertEquals("user-7", request.header("X-User-Id"));
        assertEquals("corr-analytics-1", request.header("X-Correlation-ID"));
        assertNotNull(request.header("X-Trace-Id"));
        assertTrue(!request.header("X-Trace-Id").isBlank());
        assertNotNull(request.header("X-Service-Token"));
        assertTrue(!request.header("X-Service-Token").isBlank());
    }

    @Test
    void authAliasRewritesApiV1SecurityPrefixToUpstreamAuthPrefix() {
        SECURITY_SERVER.setHandler(request -> RecordingHttpServer.StubResponse.json(200, "{\"refreshed\":true}"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/v1/security/auth/refresh"),
                HttpMethod.POST,
                new HttpEntity<>("{\"refreshToken\":\"smoke\"}", headers),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"refreshed\":true}", response.getBody());

        RecordingHttpServer.RecordedRequest request = SECURITY_SERVER.lastRequest();
        assertNotNull(request);
        assertEquals("/auth/refresh", request.path());
        assertEquals("{\"refreshToken\":\"smoke\"}", request.bodyAsString());
    }

    @Test
    void toolTodoRoutesUsePlannerService() {
        PLANNER_SERVER.setHandler(request -> RecordingHttpServer.StubResponse.json(200, "{\"source\":\"planner\"}"));

        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/v1/tools/todo/list"), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"source\":\"planner\"}", response.getBody());

        RecordingHttpServer.RecordedRequest plannerRequest = PLANNER_SERVER.lastRequest();
        assertNotNull(plannerRequest);
        assertEquals("/api/v1/tools/todo/list", plannerRequest.path());
        assertNull(LIFE_TRACKER_SERVER.lastRequest());
    }

    @Test
    void toolCalendarRoutesUseLifeTrackerService() {
        LIFE_TRACKER_SERVER.setHandler(request -> RecordingHttpServer.StubResponse.json(200, "{\"source\":\"life\"}"));

        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/v1/tools/calendar/upcoming"), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("{\"source\":\"life\"}", response.getBody());

        RecordingHttpServer.RecordedRequest lifeTrackerRequest = LIFE_TRACKER_SERVER.lastRequest();
        assertNotNull(lifeTrackerRequest);
        assertEquals("/api/v1/tools/calendar/upcoming", lifeTrackerRequest.path());
        assertNull(PLANNER_SERVER.lastRequest());
    }

    @Test
    void memoryRoutesReturnExplicitFeatureDisabledEnvelope() throws Exception {
        ResponseEntity<String> response = postJson("/api/v1/tools/memory/search", "{\"query\":\"project plan\"}");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        Map<String, Object> body = jsonBody(response);
        assertEquals("FEATURE_DISABLED", body.get("error"));
        assertEquals("memory-tooling", body.get("capability"));
        assertEquals("memory-service", body.get("upstreamService"));
        assertEquals("local", body.get("runtimeMode"));
    }

    @Test
    void llmRoutesReturnExplicitFeatureDisabledEnvelope() throws Exception {
        ResponseEntity<String> response = postJson("/api/v1/llm/chat/completions", "{\"prompt\":\"hello\"}");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        Map<String, Object> body = jsonBody(response);
        assertEquals("FEATURE_DISABLED", body.get("error"));
        assertEquals("llm", body.get("capability"));
        assertEquals("llm-service", body.get("upstreamService"));
    }

    @Test
    void visionSecurityRoutesReturnExplicitFeatureDisabledEnvelope() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/v1/vision-security/status"), String.class);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        Map<String, Object> body = jsonBody(response);
        assertEquals("FEATURE_DISABLED", body.get("error"));
        assertEquals("vision-security", body.get("capability"));
        assertEquals("vision-security-service", body.get("upstreamService"));
    }

    @Test
    void upstreamAuthFailuresAreMappedExplicitly() throws Exception {
        ANALYTICS_SERVER.setHandler(request -> RecordingHttpServer.StubResponse.json(401, "{\"error\":\"token_expired\"}"));

        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/v1/analytics/private"), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        Map<String, Object> body = jsonBody(response);
        assertEquals("UPSTREAM_AUTH_FAILURE", body.get("error"));
        assertEquals("analytics-service", body.get("upstreamService"));
        assertEquals(401, body.get("upstreamStatus"));
        assertEquals("token_expired", ((Map<?, ?>) body.get("upstreamBody")).get("error"));
    }

    @Test
    void capabilitiesEndpointReportsDegradedOptionalRoutes() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/v1/capabilities"), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = jsonBody(response);
        assertEquals("api-gateway", body.get("service"));
        assertEquals("local", body.get("runtimeMode"));
        assertEquals("degraded", body.get("status"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> routes = (List<Map<String, Object>>) body.get("routes");
        Map<String, Object> llmRoute = findRoute(routes, "/api/v1/llm/**");
        Map<String, Object> memoryToolsRoute = findRoute(routes, "/api/v1/tools/**");
        Map<String, Object> pcControlWs = findRoute(routes, "/ws/pc-control");

        assertEquals("disabled", llmRoute.get("status"));
        assertEquals("partially-degraded", memoryToolsRoute.get("status"));
        assertEquals("session-dependent", pcControlWs.get("status"));
    }

    private ResponseEntity<String> postJson(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
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
