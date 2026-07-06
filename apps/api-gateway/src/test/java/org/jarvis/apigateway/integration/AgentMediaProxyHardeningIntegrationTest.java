package org.jarvis.apigateway.integration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * api-gateway hardening #8 — end-to-end coverage for the agent-service and
 * media-service proxy routes added earlier: authentication is enforced, the
 * internal X-Service-Token is attached (and the user's own bearer token is not
 * leaked upstream), the configured desktop-app CORS origin is honoured, and
 * springdoc actually serves the routes it documents.
 */
@SpringBootTest(
        classes = ApiGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=",
                "JWT_SECRET=0123456789012345678901234567890123456789012345678901234567890123",
                "jarvis.jwt.secret=0123456789012345678901234567890123456789012345678901234567890123",
                "jwt.secret=0123456789012345678901234567890123456789012345678901234567890123",
                "jarvis.jwt.enabled=true",
                "jarvis.jwt.issuer=jarvis",
                "service.jwt.secret=service-secret-01234567890123456789012345678901",
                "services.memory.enabled=false",
                "services.llm.enabled=false",
                "services.vision-security.enabled=false",
                "jarvis.runtime.mode=local",
                // Enable CORS with the desktop origin so the CorsConfigurationSource bean
                // produces a real config (prod default is cors.enabled=false / no origins).
                "cors.enabled=true",
                "cors.allowed-origins=https://desktop.jarvis.local"
        })
@ActiveProfiles("test")
class AgentMediaProxyHardeningIntegrationTest {

    private static final String TEST_JWT_SECRET =
            "0123456789012345678901234567890123456789012345678901234567890123";

    private static final RecordingHttpServer AGENT_SERVER = RecordingHttpServer.start();
    private static final RecordingHttpServer MEDIA_SERVER = RecordingHttpServer.start();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @MockBean
    private VoiceWebSocketProxyHandler voiceWebSocketProxyHandler;

    @MockBean
    private PcControlWebSocketHandler pcControlWebSocketHandler;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("services.agent-service.url", AGENT_SERVER::baseUrl);
        registry.add("services.media-service.url", MEDIA_SERVER::baseUrl);
        registry.add("services.security.url", () -> "http://127.0.0.1:65540");
        registry.add("services.analytics.url", () -> "http://127.0.0.1:65541");
        registry.add("services.life-tracker.url", () -> "http://127.0.0.1:65530");
        registry.add("services.llm.url", () -> "http://127.0.0.1:65531");
        registry.add("services.memory.url", () -> "http://127.0.0.1:65532");
        registry.add("services.nlp-service.url", () -> "http://127.0.0.1:65533");
        registry.add("services.orchestrator.url", () -> "http://127.0.0.1:65534");
        registry.add("services.pc-control.url", () -> "http://127.0.0.1:65535");
        registry.add("services.planner.url", () -> "http://127.0.0.1:65526");
        registry.add("services.smart-home.url", () -> "http://127.0.0.1:65527");
        registry.add("services.vision-security.url", () -> "http://127.0.0.1:65528");
        registry.add("services.voice-gateway.url", () -> "http://127.0.0.1:65529");
    }

    @BeforeEach
    void resetServers() {
        AGENT_SERVER.reset();
        MEDIA_SERVER.reset();
    }

    @AfterAll
    static void shutdownServers() {
        AGENT_SERVER.close();
        MEDIA_SERVER.close();
    }

    @Test
    void agentRouteRequiresAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/v1/agents/roles"), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNull(AGENT_SERVER.lastRequest());
    }

    @Test
    void mediaRouteRequiresAuthentication() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/v1/media/probe"),
                HttpMethod.POST,
                new HttpEntity<>("{}", headers),
                String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNull(MEDIA_SERVER.lastRequest());
    }

    @Test
    void agentRouteAttachesServiceTokenAndDropsUserBearerToken() {
        AGENT_SERVER.setHandler(request -> RecordingHttpServer.StubResponse.json(200, "{\"roles\":[]}"));
        String accessToken = buildAccessToken("user-1", "alice", Instant.now().plusSeconds(300));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/v1/agents/roles"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        RecordingHttpServer.RecordedRequest upstreamRequest = AGENT_SERVER.lastRequest();
        assertNotNull(upstreamRequest);
        assertNull(upstreamRequest.header("Authorization"));
        assertNotNull(upstreamRequest.header("X-Service-Token"));
        assertTrue(!upstreamRequest.header("X-Service-Token").isBlank());
        assertEquals("user-1", upstreamRequest.header("X-User-Id"));
        assertEquals("alice", upstreamRequest.header("X-Username"));
    }

    @Test
    void mediaRouteAttachesServiceTokenAndDropsUserBearerToken() {
        MEDIA_SERVER.setHandler(request -> RecordingHttpServer.StubResponse.json(200, "{\"streams\":[]}"));
        String accessToken = buildAccessToken("user-2", "bob", Instant.now().plusSeconds(300));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/v1/media/probe"),
                HttpMethod.POST,
                new HttpEntity<>("{\"path\":\"/tmp/in.mp4\"}", headers),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        RecordingHttpServer.RecordedRequest upstreamRequest = MEDIA_SERVER.lastRequest();
        assertNotNull(upstreamRequest);
        assertNull(upstreamRequest.header("Authorization"));
        assertNotNull(upstreamRequest.header("X-Service-Token"));
        assertTrue(!upstreamRequest.header("X-Service-Token").isBlank());
        assertEquals("user-2", upstreamRequest.header("X-User-Id"));
        assertEquals("bob", upstreamRequest.header("X-Username"));
    }

    @Test
    void corsAllowsConfiguredDesktopOriginOnAgentRoute() {
        AGENT_SERVER.setHandler(request -> RecordingHttpServer.StubResponse.json(200, "{\"roles\":[]}"));
        String accessToken = buildAccessToken("user-3", "carol", Instant.now().plusSeconds(300));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set(HttpHeaders.ORIGIN, "https://desktop.jarvis.local");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/v1/agents/roles"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("https://desktop.jarvis.local",
                response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void corsRejectsUnconfiguredOriginOnAgentRoute() {
        // Spring's CorsFilter runs ahead of JwtAuthFilter in the security chain and rejects a
        // mismatched Origin outright (403) before authentication is even evaluated — no bearer
        // token needed to demonstrate this.
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, "https://not-allowed.example.com");

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/v1/agents/roles"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNull(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertNull(AGENT_SERVER.lastRequest());
    }

    @Test
    void openApiDocsListAgentAndMediaProxyRoutes() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/v3/api-docs"), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        String body = response.getBody();
        assertTrue(body.contains("/api/v1/agents"));
        assertTrue(body.contains("/api/v1/media"));
        // Tag names are independent of how springdoc renders the wildcard path patterns, so
        // this is a second, more format-resilient signal that both controllers were scanned.
        assertTrue(body.contains("Agent Service Proxy"));
        assertTrue(body.contains("Media Service Proxy"));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private String buildAccessToken(String userId, String username, Instant expiresAt) {
        Instant now = Instant.now();
        SecretKey secretKey = Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8));

        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .claim("role", "USER")
                .claim("roles", List.of("USER"))
                .claim("type", "access")
                .claim("token_type", "access")
                .issuer("jarvis")
                .issuedAt(Date.from(now.minusSeconds(1)))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();
    }
}
