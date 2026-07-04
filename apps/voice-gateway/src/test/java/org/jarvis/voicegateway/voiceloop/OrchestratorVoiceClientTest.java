package org.jarvis.voicegateway.voiceloop;

import org.jarvis.commands.voice.VoiceSessionStatus;
import org.jarvis.common.security.ServiceJwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class OrchestratorVoiceClientTest {

    private static final String URL = "http://orchestrator:8083/api/v1/orchestrator/voice/dispatch";

    private ServiceJwtProvider serviceJwtProvider;
    private OrchestratorVoiceClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        serviceJwtProvider = mock(ServiceJwtProvider.class);
        client = new OrchestratorVoiceClient(URL, 30000, "voice-gateway", serviceJwtProvider);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    void dispatchParsesSuccessfulReplyAndAttachesServiceToken() {
        when(serviceJwtProvider.isEnabled()).thenReturn(true);
        when(serviceJwtProvider.createToken("voice-gateway", java.util.List.of("SVC_INTERNAL")))
                .thenReturn("svc-token");

        server.expect(requestTo(URL))
                .andExpect(method(POST))
                .andExpect(header("X-Service-Token", "svc-token"))
                .andRespond(withStatus(OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"commandId\":\"cmd-1\",\"correlationId\":\"corr-1\",\"sessionStatus\":\"COMPLETED\","
                                + "\"feedback\":{\"code\":\"SUCCESS\",\"level\":\"INFO\",\"spokenText\":\"Готово\",\"displayText\":\"done\"}}"));

        OrchestratorVoiceClient.VoiceLoopReply reply = client.dispatch(
                "sess-1", "user-1", "corr-1", "VOLUME_UP", "громче");

        assertEquals("cmd-1", reply.commandId());
        assertEquals("corr-1", reply.correlationId());
        assertEquals(VoiceSessionStatus.COMPLETED, reply.status());
        assertEquals("SUCCESS", reply.feedback().getCode());
        assertEquals("Готово", reply.feedback().getSpokenText());
        server.verify();
    }

    @Test
    void dispatchDoesNotAttachServiceTokenWhenDisabled() {
        when(serviceJwtProvider.isEnabled()).thenReturn(false);

        server.expect(requestTo(URL))
                .andExpect(method(POST))
                .andRespond(withStatus(OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"sessionStatus\":\"COMPLETED\"}"));

        OrchestratorVoiceClient.VoiceLoopReply reply = client.dispatch(
                "sess-1", "user-1", "corr-1", "GREETING", "привет");

        assertEquals(VoiceSessionStatus.COMPLETED, reply.status());
        server.verify();
    }

    @Test
    void dispatchReturnsUnknownFeedbackWhenFeedbackMissing() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"sessionStatus\":\"COMPLETED\"}"));

        OrchestratorVoiceClient.VoiceLoopReply reply = client.dispatch(
                "sess-1", "user-1", "corr-1", "GREETING", "привет");

        assertEquals("UNKNOWN", reply.feedback().getCode());
    }

    @Test
    void dispatchDefaultsToFailedStatusWhenSessionStatusMissing() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"));

        OrchestratorVoiceClient.VoiceLoopReply reply = client.dispatch(
                "sess-1", "user-1", "corr-1", "GREETING", "привет");

        assertEquals(VoiceSessionStatus.FAILED, reply.status());
    }

    @Test
    void dispatchReturnsFailedReplyWhenBodyIsNull() {
        server.expect(requestTo(URL)).andRespond(withStatus(OK));

        OrchestratorVoiceClient.VoiceLoopReply reply = client.dispatch(
                "sess-1", "user-1", "corr-1", "GREETING", "привет");

        assertEquals(VoiceSessionStatus.FAILED, reply.status());
        assertNull(reply.commandId());
        assertNotNull(reply.feedback());
    }

    @Test
    void dispatchReturnsFailedReplyWhenOrchestratorUnreachable() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        OrchestratorVoiceClient.VoiceLoopReply reply = client.dispatch(
                "sess-1", "user-1", "corr-1", "GREETING", "привет");

        assertEquals(VoiceSessionStatus.FAILED, reply.status());
        assertEquals("FAILED", reply.feedback().getCode());
    }
}
