package org.jarvis.apigateway.integration;

import org.jarvis.apigateway.ApiGatewayApplication;
import org.jarvis.apigateway.proxy.ProxyTimeoutPolicy;
import org.jarvis.apigateway.proxy.ProxyTimeoutProperties;
import org.jarvis.apigateway.support.RecordingHttpServer;
import org.jarvis.apigateway.websocket.PcControlWebSocketHandler;
import org.jarvis.apigateway.websocket.VoiceWebSocketProxyHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
        classes = {ApiGatewayApplication.class, GatewayIngressTimeoutIntegrationTest.ShortTimeoutConfig.class},
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
class GatewayIngressTimeoutIntegrationTest {

    private static final RecordingHttpServer ANALYTICS_SERVER = RecordingHttpServer.start();

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
        registry.add("services.analytics.url", ANALYTICS_SERVER::baseUrl);
    }

    @BeforeEach
    void resetServer() {
        ANALYTICS_SERVER.reset();
    }

    @AfterAll
    static void shutdownServer() {
        ANALYTICS_SERVER.close();
    }

    @Test
    void slowUpstreamResponsesBecomeExplicitGatewayTimeouts() {
        ANALYTICS_SERVER.setHandler(request -> {
            Thread.sleep(250L);
            return RecordingHttpServer.StubResponse.json(200, "{\"service\":\"analytics\"}");
        });

        ResponseEntity<Map> response = restTemplate.getForEntity(url("/api/v1/analytics/slow"), Map.class);

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.getStatusCode());
        assertEquals("UPSTREAM_TIMEOUT", response.getBody().get("error"));
        assertEquals("analytics-service", response.getBody().get("upstreamService"));
    }

    @TestConfiguration
    static class ShortTimeoutConfig {
        @Bean
        @Primary
        ProxyTimeoutPolicy proxyTimeoutPolicy() {
            return new ProxyTimeoutPolicy(new ProxyTimeoutProperties()) {
                @Override
                public Duration connectTimeout(String downstreamService) {
                    return Duration.ofMillis(100);
                }

                @Override
                public Duration readTimeout(String downstreamService) {
                    return Duration.ofMillis(100);
                }
            };
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
