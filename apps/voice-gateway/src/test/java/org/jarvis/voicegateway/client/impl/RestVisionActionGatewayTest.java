package org.jarvis.voicegateway.client.impl;

import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.voicegateway.rules.VoiceCommandActionDispatcher.DispatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Verifies the vision gateway turns the orchestrator's ask-screen JSON contract into a
 * {@link DispatchResult}: {@code analyzed} → spoken answer as override, {@code vision_failed} /
 * transport error → coded failure reason.
 */
class RestVisionActionGatewayTest {

    private static final String URL = "http://orchestrator:8083/internal/vision/ask-screen";
    private static final String EMPTY_SCREEN_TEXT = "Готово, сэр. На экране нет распознаваемого текста.";

    private ServiceJwtProvider serviceJwtProvider;
    private RestVisionActionGateway gateway;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        serviceJwtProvider = mock(ServiceJwtProvider.class);
        when(serviceJwtProvider.createToken("voice-gateway", java.util.List.of("SVC_INTERNAL")))
                .thenReturn("svc-token");

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        gateway = new RestVisionActionGateway(builder, serviceJwtProvider);
        ReflectionTestUtils.setField(gateway, "visionDispatchUrl", "http://orchestrator:8083");
        ReflectionTestUtils.setField(gateway, "serviceName", "voice-gateway");
    }

    @Test
    void analyzedResponseSurfacesAnswerAsOverride() {
        server.expect(requestTo(URL))
                .andExpect(method(POST))
                .andExpect(header("X-Service-Token", "svc-token"))
                .andExpect(jsonPath("$.question").value("Что на экране?"))
                .andExpect(jsonPath("$.userId").value("user-1"))
                .andExpect(jsonPath("$.correlationId").value("corr-1"))
                .andRespond(withStatus(OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"analyzed\",\"answer\":\"На экране браузер и редактор кода.\"}"));

        DispatchResult result = gateway.askScreen("user-1", "Что на экране?", "corr-1");

        assertTrue(result.executionSucceeded());
        assertFalse(result.executionFailed());
        assertEquals("На экране браузер и редактор кода.", result.responseTextOverride());
        server.verify();
    }

    @Test
    void analyzedWithBlankAnswerFallsBackToEmptyScreenText() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"analyzed\",\"answer\":\"\"}"));

        DispatchResult result = gateway.askScreen("user-1", "Что на экране?", "corr-1");

        assertTrue(result.executionSucceeded());
        assertEquals(EMPTY_SCREEN_TEXT, result.responseTextOverride());
    }

    @Test
    void visionFailedSurfacesCodedFailureReason() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"vision_failed\",\"answer\":\"\",\"failureReason\":\"VISION_UNAVAILABLE\"}"));

        DispatchResult result = gateway.askScreen("user-1", "Что на экране?", "corr-1");

        assertFalse(result.executionSucceeded());
        assertTrue(result.executionFailed());
        assertEquals("VISION_UNAVAILABLE", result.failureReason());
    }

    @Test
    void visionFailedWithoutReasonDefaultsToUnavailable() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"vision_failed\",\"answer\":\"\"}"));

        DispatchResult result = gateway.askScreen("user-1", "Что на экране?", "corr-1");

        assertTrue(result.executionFailed());
        assertEquals("VISION_UNAVAILABLE", result.failureReason());
    }

    @Test
    void httpErrorIsPreservedAsCodedVisionHttpReason() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        DispatchResult result = gateway.askScreen("user-1", "Что на экране?", "corr-1");

        assertTrue(result.executionFailed());
        assertEquals("VISION_HTTP_500", result.failureReason());
    }

    @Test
    void emptyBodyIsTreatedAsUnavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(OK));

        DispatchResult result = gateway.askScreen("user-1", "Что на экране?", "corr-1");

        assertTrue(result.executionFailed());
        assertEquals("VISION_UNAVAILABLE", result.failureReason());
    }
}
