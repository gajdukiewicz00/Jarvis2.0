package org.jarvis.planner.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class VoiceNotificationClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private VoiceNotificationClient voiceNotificationClient;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        voiceNotificationClient = new VoiceNotificationClient(restTemplate, "http://voice-gateway:8081");
    }

    @Test
    void sendNotificationReturnsTrueWhenVoiceGatewayRespondsSuccessfully() {
        server.expect(requestTo("http://voice-gateway:8081/internal/voice/notify"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        boolean delivered = voiceNotificationClient.sendNotification("user-1", "Напоминание", "ru");

        assertTrue(delivered);
        server.verify();
    }

    @Test
    void sendNotificationReturnsFalseWhenVoiceGatewayReturnsClientError() {
        server.expect(requestTo("http://voice-gateway:8081/internal/voice/notify"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        boolean delivered = voiceNotificationClient.sendNotification("user-1", "Напоминание", "ru");

        assertFalse(delivered);
        server.verify();
    }

    @Test
    void sendNotificationReturnsFalseWhenRequestFailsWithIoError() {
        server.expect(requestTo("http://voice-gateway:8081/internal/voice/notify"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    throw new IOException("connection refused");
                });

        boolean delivered = voiceNotificationClient.sendNotification("user-1", "Напоминание", "ru");

        assertFalse(delivered);
        server.verify();
    }
}
