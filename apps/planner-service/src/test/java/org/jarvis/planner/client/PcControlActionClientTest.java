package org.jarvis.planner.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PcControlActionClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private PcControlActionClient pcControlActionClient;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        pcControlActionClient = new PcControlActionClient(restTemplate, "http://api-gateway:8080");
    }

    @Test
    void sendActionReturnsTrueWhenApiGatewayRespondsSuccessfully() {
        server.expect(requestTo("http://api-gateway:8080/internal/pc-control/action"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        boolean routed = pcControlActionClient.sendAction("user-1", "SCENARIO", Map.of("name", "focus"));

        assertTrue(routed);
        server.verify();
    }

    @Test
    void sendActionReturnsFalseWhenResponseIsNotSuccessful() {
        server.expect(requestTo("http://api-gateway:8080/internal/pc-control/action"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FOUND));

        boolean routed = pcControlActionClient.sendAction("user-1", "SCENARIO", Map.of("name", "focus"));

        assertFalse(routed);
        server.verify();
    }

    @Test
    void sendActionReturnsFalseWhenApiGatewayReturnsServerError() {
        server.expect(requestTo("http://api-gateway:8080/internal/pc-control/action"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        boolean routed = pcControlActionClient.sendAction("user-1", "SCENARIO", Map.of("name", "focus"));

        assertFalse(routed);
        server.verify();
    }

    @Test
    void sendActionReturnsFalseWhenRequestFailsWithIoError() {
        server.expect(requestTo("http://api-gateway:8080/internal/pc-control/action"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    throw new IOException("connection refused");
                });

        boolean routed = pcControlActionClient.sendAction("user-1", "SCENARIO", Map.of("name", "focus"));

        assertFalse(routed);
        server.verify();
    }
}
