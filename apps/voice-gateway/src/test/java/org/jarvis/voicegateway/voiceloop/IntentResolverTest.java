package org.jarvis.voicegateway.voiceloop;

import org.jarvis.common.security.ServiceJwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.HttpMethod.POST;

class IntentResolverTest {

    private static final String URL = "http://nlp-service:8082/api/v1/nlp/intent-fast";

    private ServiceJwtProvider serviceJwtProvider;
    private IntentResolver resolver;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        serviceJwtProvider = mock(ServiceJwtProvider.class);
        resolver = new IntentResolver(URL, 1500, "voice-gateway", serviceJwtProvider);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(resolver, "restTemplate");
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    void resolveReturnsEmptyForBlankTranscript() {
        IntentResolver.Resolution resolution = resolver.resolve("   ", "ru");

        assertFalse(resolution.isResolved());
        assertEquals("none", resolution.source());
    }

    @Test
    void resolveReturnsEmptyForNullTranscript() {
        IntentResolver.Resolution resolution = resolver.resolve(null, "ru");

        assertFalse(resolution.isResolved());
    }

    @Test
    void resolveParsesSuccessfulResponseAndAttachesServiceToken() {
        when(serviceJwtProvider.isEnabled()).thenReturn(true);
        when(serviceJwtProvider.createToken("voice-gateway", java.util.List.of("SVC_INTERNAL")))
                .thenReturn("svc-token");

        server.expect(requestTo(URL))
                .andExpect(method(POST))
                .andExpect(header("X-Service-Token", "svc-token"))
                .andExpect(jsonPath("$.text").value("сделай громче"))
                .andExpect(jsonPath("$.locale").value("ru-RU"))
                .andRespond(withStatus(OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"intent\":\"VOLUME_UP\",\"source\":\"router\",\"confidence\":0.92}"));

        IntentResolver.Resolution resolution = resolver.resolve("сделай громче", "ru-RU");

        assertEquals("VOLUME_UP", resolution.intent());
        assertEquals("router", resolution.source());
        assertEquals(0.92, resolution.confidence());
        assertTrue(resolution.isResolved());
        server.verify();
    }

    @Test
    void resolveDoesNotAttachServiceTokenWhenDisabled() {
        when(serviceJwtProvider.isEnabled()).thenReturn(false);

        server.expect(requestTo(URL))
                .andExpect(method(POST))
                .andRespond(withStatus(OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"intent\":\"GREETING\"}"));

        IntentResolver.Resolution resolution = resolver.resolve("привет", null);

        assertEquals("GREETING", resolution.intent());
        assertEquals("regex", resolution.source());
        server.verify();
    }

    @Test
    void resolveReturnsEmptyWhenResponseBodyIsNull() {
        server.expect(requestTo(URL)).andRespond(withStatus(OK));

        IntentResolver.Resolution resolution = resolver.resolve("hello", null);

        assertFalse(resolution.isResolved());
    }

    @Test
    void resolveReturnsEmptyWhenServiceUnreachable() {
        server.expect(requestTo(URL)).andRespond(request -> {
            throw new java.io.IOException("connection refused");
        });

        IntentResolver.Resolution resolution = resolver.resolve("привет", null);

        assertFalse(resolution.isResolved());
        assertEquals("none", resolution.source());
    }

    @Test
    void resolveReturnsEmptyOnServerError() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        IntentResolver.Resolution resolution = resolver.resolve("привет", null);

        assertFalse(resolution.isResolved());
    }
}
