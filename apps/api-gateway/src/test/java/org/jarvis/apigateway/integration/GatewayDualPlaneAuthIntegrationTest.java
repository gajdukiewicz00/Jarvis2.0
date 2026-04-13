package org.jarvis.apigateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                "jarvis.runtime.mode=local"
        })
@ActiveProfiles("test")
class GatewayDualPlaneAuthIntegrationTest {

    private static final String TEST_JWT_SECRET =
            "0123456789012345678901234567890123456789012345678901234567890123";

    private static final RecordingHttpServer SECURITY_SERVER = RecordingHttpServer.start();
    private static final RecordingHttpServer ANALYTICS_SERVER = RecordingHttpServer.start();

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
        registry.add("services.security.url", SECURITY_SERVER::baseUrl);
        registry.add("services.analytics.url", ANALYTICS_SERVER::baseUrl);
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
        SECURITY_SERVER.reset();
        ANALYTICS_SERVER.reset();
    }

    @AfterAll
    static void shutdownServers() {
        SECURITY_SERVER.close();
        ANALYTICS_SERVER.close();
    }

    @Test
    void gatewaySeparatesUserBearerPlaneFromInternalServicePlane() throws Exception {
        String accessToken = buildAccessToken("user-42", "alice", Instant.now().plusSeconds(300));

        SECURITY_SERVER.setHandler(request -> {
            if ("/auth/login".equals(request.path())) {
                return RecordingHttpServer.StubResponse.json(200, """
                        {
                          "accessToken": "%s",
                          "refreshToken": "refresh-1",
                          "expiresIn": 300,
                          "username": "alice",
                          "role": "USER"
                        }
                        """.formatted(accessToken));
            }
            if ("/auth/me".equals(request.path())) {
                return RecordingHttpServer.StubResponse.json(200, """
                        {
                          "id": "user-42",
                          "username": "alice",
                          "role": "USER",
                          "enabled": true
                        }
                        """);
            }
            return RecordingHttpServer.StubResponse.json(404, "{\"error\":\"not_found\"}");
        });
        ANALYTICS_SERVER.setHandler(request -> RecordingHttpServer.StubResponse.json(200, "{\"service\":\"analytics\"}"));

        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> loginResponse = restTemplate.postForEntity(
                url("/auth/login"),
                new HttpEntity<>("""
                        {
                          "username": "alice",
                          "password": "password123"
                        }
                        """, loginHeaders),
                String.class);

        assertEquals(HttpStatus.OK, loginResponse.getStatusCode());
        JsonNode loginBody = objectMapper.readTree(loginResponse.getBody());
        assertEquals(accessToken, loginBody.get("accessToken").asText());

        RecordingHttpServer.RecordedRequest loginRequest = SECURITY_SERVER.lastRequest();
        assertNotNull(loginRequest);
        assertEquals("/auth/login", loginRequest.path());
        assertNull(loginRequest.header("Authorization"));
        assertNull(loginRequest.header("X-Service-Token"));

        HttpHeaders userHeaders = new HttpHeaders();
        userHeaders.setBearerAuth(accessToken);

        ResponseEntity<String> meResponse = restTemplate.exchange(
                url("/auth/me"),
                HttpMethod.GET,
                new HttpEntity<>(userHeaders),
                String.class);

        assertEquals(HttpStatus.OK, meResponse.getStatusCode());

        RecordingHttpServer.RecordedRequest meRequest = SECURITY_SERVER.lastRequest();
        assertNotNull(meRequest);
        assertEquals("/auth/me", meRequest.path());
        assertEquals("Bearer " + accessToken, meRequest.header("Authorization"));
        assertNull(meRequest.header("X-Service-Token"));

        ResponseEntity<String> analyticsResponse = restTemplate.exchange(
                url("/api/v1/analytics/overview"),
                HttpMethod.GET,
                new HttpEntity<>(userHeaders),
                String.class);

        assertEquals(HttpStatus.OK, analyticsResponse.getStatusCode());
        assertEquals("{\"service\":\"analytics\"}", analyticsResponse.getBody());

        RecordingHttpServer.RecordedRequest analyticsRequest = ANALYTICS_SERVER.lastRequest();
        assertNotNull(analyticsRequest);
        assertEquals("/api/v1/analytics/overview", analyticsRequest.path());
        assertEquals("user-42", analyticsRequest.header("X-User-Id"));
        assertEquals("alice", analyticsRequest.header("X-Username"));
        assertEquals("USER", analyticsRequest.header("X-User-Roles"));
        assertNull(analyticsRequest.header("Authorization"));
        assertNotNull(analyticsRequest.header("X-Service-Token"));
        assertTrue(!analyticsRequest.header("X-Service-Token").isBlank());
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
