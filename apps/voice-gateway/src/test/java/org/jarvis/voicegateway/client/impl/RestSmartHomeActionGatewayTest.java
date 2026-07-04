package org.jarvis.voicegateway.client.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.common.security.ServiceJwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class RestSmartHomeActionGatewayTest {

    private static final String URL = "http://smart-home-service:8086/api/v1/smarthome/devices/kitchen_light/action";

    private ServiceJwtProvider serviceJwtProvider;
    private RestSmartHomeActionGateway gateway;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        serviceJwtProvider = mock(ServiceJwtProvider.class);
        when(serviceJwtProvider.createToken("voice-gateway", java.util.List.of("SVC_INTERNAL")))
                .thenReturn("svc-token");

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        gateway = new RestSmartHomeActionGateway(builder, serviceJwtProvider, new ObjectMapper());
        ReflectionTestUtils.setField(gateway, "smartHomeUrl", "http://smart-home-service:8086");
        ReflectionTestUtils.setField(gateway, "serviceName", "voice-gateway");
    }

    @Test
    void executeSendsActionAndSerializedMapPayload() {
        server.expect(requestTo(URL))
                .andExpect(method(POST))
                .andExpect(header("X-Service-Token", "svc-token"))
                .andExpect(header("X-User-Id", "user-1"))
                .andExpect(jsonPath("$.action").value("TURN_ON"))
                .andExpect(jsonPath("$.payload").exists())
                .andRespond(withStatus(OK));

        gateway.execute("user-1", "kitchen_light", "TURN_ON", java.util.Map.of("brightness", 80));

        server.verify();
    }

    @Test
    void executeAcceptsStringPayloadAsIs() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.payload").value("raw-string-payload"))
                .andRespond(withStatus(OK));

        gateway.execute("user-1", "kitchen_light", "TURN_ON", "raw-string-payload");

        server.verify();
    }

    @Test
    void executeSendsNullPayloadWhenPayloadIsNull() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.payload").doesNotExist())
                .andRespond(withStatus(OK));

        gateway.execute("user-1", "kitchen_light", "TURN_OFF", null);

        server.verify();
    }

    @Test
    void executeThrowsIllegalArgumentExceptionWhenPayloadCannotBeSerialized() {
        // A getter that throws during serialization is wrapped by Jackson as a
        // JsonProcessingException (SerializationFeature.WRAP_EXCEPTIONS is on by
        // default), which is exactly the failure mode serializePayload() guards against.
        assertThrows(IllegalArgumentException.class,
                () -> gateway.execute("user-1", "kitchen_light", "TURN_ON", new PoisonedPayload()));
    }

    /** POJO whose getter always throws, forcing Jackson to fail serialization. */
    public static final class PoisonedPayload {
        public String getValue() {
            throw new IllegalStateException("cannot serialize");
        }
    }
}
