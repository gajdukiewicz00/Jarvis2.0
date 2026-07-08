package org.jarvis.voicegateway.client.impl;

import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.voicegateway.client.PcControlActionGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Voice PC-control now dispatches through the orchestrator (NetworkPolicy allows
 * voice-gateway → orchestrator → api-gateway, but blocks voice-gateway → api-gateway
 * directly). HTTP/transport errors are preserved as coded DispatchResults instead of
 * being rethrown, so the WS handler can speak a specific reason.
 */
class RestPcControlActionGatewayTest {

    private static final String URL = "http://orchestrator:8083/internal/pc-control/action";

    private ServiceJwtProvider serviceJwtProvider;
    private RestPcControlActionGateway gateway;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        serviceJwtProvider = mock(ServiceJwtProvider.class);
        when(serviceJwtProvider.createToken("voice-gateway", java.util.List.of("SVC_INTERNAL")))
                .thenReturn("svc-token");

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        gateway = new RestPcControlActionGateway(builder, serviceJwtProvider);
        ReflectionTestUtils.setField(gateway, "pcDispatchUrl", "http://orchestrator:8083");
        ReflectionTestUtils.setField(gateway, "serviceName", "voice-gateway");
    }

    @Test
    void dispatchSendsActionAndParamsToOrchestratorAndParsesSuccessResult() {
        server.expect(requestTo(URL))
                .andExpect(method(POST))
                .andExpect(header("X-Service-Token", "svc-token"))
                .andExpect(jsonPath("$.action").value("VOLUME_UP"))
                .andExpect(jsonPath("$.userId").value("user-1"))
                .andExpect(jsonPath("$.correlationId").value("corr-1"))
                .andRespond(withStatus(OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"executed\",\"executorFound\":true,\"executionAttempted\":true,"
                                + "\"executionSucceeded\":true,\"executionFailed\":false}"));

        PcControlActionGateway.DispatchResult result = gateway.dispatch(
                "VOLUME_UP", Map.of("delta", 10), "user-1", "corr-1");

        assertEquals("executed", result.status());
        assertTrue(result.executionSucceeded());
        assertFalse(result.executionFailed());
        server.verify();
    }

    @Test
    void dispatchOmitsUserAndCorrelationWhenBlank() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.correlationId").doesNotExist())
                .andRespond(withStatus(OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"executed\",\"executorFound\":true,\"executionAttempted\":true,\"executionSucceeded\":true}"));

        gateway.dispatch("MUTE", null, "", "");

        server.verify();
    }

    @Test
    void dispatchReturnsEmptyResultWhenResponseBodyIsNull() {
        server.expect(requestTo(URL)).andRespond(withStatus(OK));

        PcControlActionGateway.DispatchResult result = gateway.dispatch("MUTE", Map.of(), "user-1", "corr-1");

        assertEquals("", result.status());
        assertTrue(result.executionFailed());
        assertTrue(result.failureReason().contains("EMPTY_RESPONSE"));
    }

    @Test
    void dispatchReadsFailureReasonFromMessageKeyWhenFailureReasonMissing() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"execution_failed\",\"message\":\"boom\",\"executionFailed\":true}"));

        PcControlActionGateway.DispatchResult result = gateway.dispatch("MUTE", Map.of(), "user-1", "corr-1");

        assertEquals("boom", result.failureReason());
        assertTrue(result.executionFailed());
    }

    @Test
    void dispatchServerErrorIsPreservedAsCodedFailureNotThrown() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        PcControlActionGateway.DispatchResult result = gateway.dispatch("MUTE", Map.of(), "user-1", "corr-1");

        assertTrue(result.executionFailed());
        assertFalse(result.executionSucceeded());
        assertTrue(result.failureReason().contains("HTTP_500"), result.failureReason());
    }

    @Test
    void dispatchUnauthorizedIsPreservedAsHttp401() {
        server.expect(requestTo(URL)).andRespond(withStatus(UNAUTHORIZED));

        PcControlActionGateway.DispatchResult result = gateway.dispatch("VOLUME_UP", Map.of(), "user-1", "corr-1");

        assertTrue(result.executionFailed());
        assertTrue(result.failureReason().contains("HTTP_401"), result.failureReason());
    }

    @Test
    void dispatchForbiddenIsPreservedAsHttp403() {
        server.expect(requestTo(URL)).andRespond(withStatus(FORBIDDEN));

        PcControlActionGateway.DispatchResult result = gateway.dispatch("VOLUME_UP", Map.of(), "user-1", "corr-1");

        assertTrue(result.executionFailed());
        assertTrue(result.failureReason().contains("HTTP_403"), result.failureReason());
    }

    @Test
    void dispatchTreatsStringBooleanAsExecutorFound() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"executed\",\"executorFound\":\"true\",\"executionAttempted\":\"true\",\"executionSucceeded\":\"true\"}"));

        PcControlActionGateway.DispatchResult result = gateway.dispatch("MUTE", Map.of(), "user-1", "corr-1");

        assertTrue(result.executorFound());
        assertTrue(result.executionSucceeded());
    }
}
