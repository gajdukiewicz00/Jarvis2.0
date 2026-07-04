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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class RestOrchestratorClientTest {

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
    void sendCommandPostsTextAndServiceToken() {
        server.expect(requestTo("http://orchestrator:8083/api/v1/orchestrator/execute"))
                .andExpect(method(POST))
                .andExpect(header("X-Service-Token", "svc-token"))
                .andRespond(withStatus(OK));

        client.sendCommand("сделай громче");

        server.verify();
    }

    @Test
    void sendCommandWithUserIdAddsUserHeader() {
        server.expect(requestTo("http://orchestrator:8083/api/v1/orchestrator/execute"))
                .andExpect(method(POST))
                .andExpect(header("X-User-Id", "user-1"))
                .andRespond(withStatus(OK));

        client.sendCommand("сделай громче", "user-1");

        server.verify();
    }

    @Test
    void sendCommandWrapsHttpErrorAsRuntimeException() {
        server.expect(requestTo("http://orchestrator:8083/api/v1/orchestrator/execute"))
                .andRespond(withServerError());

        RuntimeException ex = assertThrows(RuntimeException.class, () -> client.sendCommand("text"));
        assertTrue(ex.getMessage().contains("Failed to call orchestrator"));
    }

    @Test
    void sendCommandWrapsConnectionFailureAsRuntimeException() {
        server.expect(requestTo("http://orchestrator:8083/api/v1/orchestrator/execute"))
                .andRespond(request -> {
                    throw new java.io.IOException("refused");
                });

        assertThrows(RuntimeException.class, () -> client.sendCommand("text"));
    }

    @Test
    void sendCommandWrapsUnknownRestClientExceptionAsRuntimeException() {
        // An out-of-range HTTP status makes Spring throw UnknownHttpStatusCodeException,
        // which is a RestClientException but NOT an HttpStatusCodeException/
        // ResourceAccessException — exercising sendCommand's third, more general catch block.
        server.expect(requestTo("http://orchestrator:8083/api/v1/orchestrator/execute"))
                .andRespond(withRawStatus(490));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> client.sendCommand("text"));
        assertTrue(ex.getMessage().contains("Failed to call orchestrator"));
    }

    @Test
    void sendCommandRethrowsNonRestClientRuntimeExceptionUnchanged() {
        // A failure while minting the service token (before any HTTP call happens) is a
        // plain RuntimeException, not a RestClientException — exercising sendCommand's
        // final catch-all branch, which rethrows as-is instead of wrapping.
        when(serviceJwtProvider.createToken("voice-gateway", java.util.List.of("SVC_INTERNAL")))
                .thenThrow(new IllegalStateException("service jwt disabled"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> client.sendCommand("text"));
        assertEquals("service jwt disabled", ex.getMessage());
    }

    @Test
    void sendCommandWithResponseReturnsBody() {
        server.expect(requestTo("http://orchestrator:8083/api/v1/orchestrator/execute"))
                .andExpect(method(POST))
                .andRespond(withStatus(OK).body("Done, sir."));

        String response = client.sendCommandWithResponse("сделай громче");

        assertEquals("Done, sir.", response);
    }

    @Test
    void sendCommandWithResponseAndUserIdAddsHeader() {
        server.expect(requestTo("http://orchestrator:8083/api/v1/orchestrator/execute"))
                .andExpect(header("X-User-Id", "user-2"))
                .andRespond(withStatus(OK).body("ok"));

        client.sendCommandWithResponse("text", "user-2");
        server.verify();
    }

    @Test
    void sendCommandWithResponseWrapsServerError() {
        server.expect(requestTo("http://orchestrator:8083/api/v1/orchestrator/execute"))
                .andRespond(withStatus(INTERNAL_SERVER_ERROR));

        assertThrows(RuntimeException.class, () -> client.sendCommandWithResponse("text"));
    }

    @Test
    void sendCommandWithResponseWrapsConnectionFailure() {
        server.expect(requestTo("http://orchestrator:8083/api/v1/orchestrator/execute"))
                .andRespond(request -> {
                    throw new java.io.IOException("refused");
                });

        RuntimeException ex = assertThrows(RuntimeException.class, () -> client.sendCommandWithResponse("text"));
        assertTrue(ex.getMessage().contains("Failed to call orchestrator"));
    }

    @Test
    void sendIntentDetailedReturnsParsedResult() {
        server.expect(requestTo("http://orchestrator:8083/api/v1/orchestrator/execute-detailed"))
                .andExpect(method(POST))
                .andExpect(header("X-Model-Profile", "voice-fast"))
                .andExpect(header("X-Correlation-ID", "corr-1"))
                .andRespond(withStatus(OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"responseText\":\"Готово\",\"executorFound\":true,\"executionAttempted\":true,"
                                + "\"executionSucceeded\":true,\"executionFailed\":false,\"failureReason\":null}"));

        OrchestratorClient.IntentExecutionResult result = client.sendIntentDetailed(
                "VOLUME_UP", Map.of("delta", 10), "ru", "corr-1", "громче", "user-1");

        assertEquals("Готово", result.responseText());
        assertTrue(result.executionSucceeded());
        server.verify();
    }

    @Test
    void sendIntentDetailedReturnsEmptyResultWhenBodyIsNull() {
        server.expect(requestTo("http://orchestrator:8083/api/v1/orchestrator/execute-detailed"))
                .andRespond(withStatus(OK));

        OrchestratorClient.IntentExecutionResult result = client.sendIntentDetailed(
                "VOLUME_UP", null, "ru", "corr-1", null, null);

        assertFalse(result.executionSucceeded());
        assertTrue(result.executionFailed());
    }

    @Test
    void sendIntentDetailedWrapsHttpStatusError() {
        server.expect(requestTo("http://orchestrator:8083/api/v1/orchestrator/execute-detailed"))
                .andRespond(withServerError());

        assertThrows(RuntimeException.class, () -> client.sendIntentDetailed(
                "VOLUME_UP", Map.of(), "ru", "corr-1", null, null));
    }

    @Test
    void sendIntentDetailedWrapsConnectionFailure() {
        server.expect(requestTo("http://orchestrator:8083/api/v1/orchestrator/execute-detailed"))
                .andRespond(request -> {
                    throw new java.io.IOException("refused");
                });

        RuntimeException ex = assertThrows(RuntimeException.class, () -> client.sendIntentDetailed(
                "VOLUME_UP", Map.of(), "ru", "corr-1", null, null));
        assertTrue(ex.getMessage().contains("Failed to call orchestrator"));
    }

    @Test
    void sendIntentDetailedRethrowsNonRestClientRuntimeExceptionUnchanged() {
        when(serviceJwtProvider.createToken("voice-gateway", java.util.List.of("SVC_INTERNAL")))
                .thenThrow(new IllegalStateException("service jwt disabled"));

        assertThrows(IllegalStateException.class, () -> client.sendIntentDetailed(
                "VOLUME_UP", Map.of(), "ru", "corr-1", null, null));
    }

    @Test
    void sendIntentDelegatesToDetailedAndReturnsResponseText() {
        server.expect(requestTo("http://orchestrator:8083/api/v1/orchestrator/execute-detailed"))
                .andRespond(withStatus(OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"responseText\":\"ok\",\"executorFound\":true,\"executionAttempted\":true,"
                                + "\"executionSucceeded\":true,\"executionFailed\":false}"));

        String response = client.sendIntent("VOLUME_UP", Map.of(), "ru", "corr-1");

        assertEquals("ok", response);
    }

    @Test
    void sendIntentWithOriginalTextDelegatesToDetailed() {
        server.expect(requestTo("http://orchestrator:8083/api/v1/orchestrator/execute-detailed"))
                .andRespond(withStatus(OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"responseText\":\"ok2\",\"executorFound\":true,\"executionAttempted\":true,"
                                + "\"executionSucceeded\":true,\"executionFailed\":false}"));

        String response = client.sendIntent("VOLUME_UP", Map.of(), "ru", "corr-1", "громче");

        assertEquals("ok2", response);
    }
}
