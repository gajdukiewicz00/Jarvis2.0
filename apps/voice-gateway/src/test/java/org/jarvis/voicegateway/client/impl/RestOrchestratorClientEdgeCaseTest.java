package org.jarvis.voicegateway.client.impl;

import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.voicegateway.client.OrchestratorClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Supplements {@link RestOrchestratorClientTest} with branches it does not
 * yet exercise on {@code sendIntentDetailed}: the null-correlationId header
 * fallback to an empty string, and the blank/absent userId header-omission
 * branch (both on {@code sendIntentDetailed} and {@code sendCommand}).
 */
class RestOrchestratorClientEdgeCaseTest {

    private ServiceJwtProvider serviceJwtProvider;
    private RestOrchestratorClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        serviceJwtProvider = mock(ServiceJwtProvider.class);
        when(serviceJwtProvider.createToken("voice-gateway", java.util.List.of("SVC_INTERNAL")))
                .thenReturn("svc-token");

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        client = new RestOrchestratorClient(builder, serviceJwtProvider);
        ReflectionTestUtils.setField(client, "orchestratorUrl", "http://orchestrator:8083");
        ReflectionTestUtils.setField(client, "serviceName", "voice-gateway");
    }

    @Test
    void sendIntentDetailedUsesEmptyCorrelationHeaderWhenCorrelationIdIsNull() {
        server.expect(requestTo("http://orchestrator:8083/api/v1/orchestrator/execute-detailed"))
                .andExpect(method(POST))
                .andExpect(header("X-Correlation-ID", ""))
                .andRespond(withStatus(OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"responseText\":\"ok\",\"executorFound\":true,\"executionAttempted\":true,"
                                + "\"executionSucceeded\":true,\"executionFailed\":false}"));

        OrchestratorClient.IntentExecutionResult result = client.sendIntentDetailed(
                "VOLUME_UP", Map.of(), "ru", null, null, null);

        assertTrue(result.executionSucceeded());
        server.verify();
    }

    @Test
    void sendIntentDetailedOmitsUserHeaderWhenUserIdIsBlank() {
        server.expect(requestTo("http://orchestrator:8083/api/v1/orchestrator/execute-detailed"))
                .andExpect(headerDoesNotExist("X-User-Id"))
                .andRespond(withStatus(OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"responseText\":\"ok\",\"executorFound\":true,\"executionAttempted\":true,"
                                + "\"executionSucceeded\":true,\"executionFailed\":false}"));

        client.sendIntentDetailed("VOLUME_UP", Map.of(), "ru", "corr-1", null, "   ");

        server.verify();
    }

    @Test
    void sendCommandOmitsUserHeaderWhenUserIdIsBlank() {
        server.expect(requestTo("http://orchestrator:8083/api/v1/orchestrator/execute"))
                .andExpect(headerDoesNotExist("X-User-Id"))
                .andRespond(withStatus(OK));

        client.sendCommand("text", "   ");

        server.verify();
    }
}
