package org.jarvis.apigateway.integration;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
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
                "service.jwt.secret=service-secret-01234567890123456789012345678901"
        })
@ActiveProfiles("dev")
class GatewayIngressUnavailableIntegrationTest {

    private static final int UNUSED_PORT = reserveUnusedPort();

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
        registry.add("services.analytics.url", () -> "http://127.0.0.1:" + UNUSED_PORT);
    }

    @Test
    void unreachableUpstreamsReturnExplicitConnectionRefusedErrors() {
        ResponseEntity<Map> response = restTemplate.getForEntity(url("/api/v1/analytics/unavailable"), Map.class);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("UPSTREAM_CONNECTION_REFUSED", response.getBody().get("error"));
        assertEquals("analytics-service", response.getBody().get("upstreamService"));
    }

    private static int reserveUnusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to reserve an unused local port", e);
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
