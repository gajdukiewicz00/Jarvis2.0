package org.jarvis.apigateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.jarvis.apigateway.client.VoiceGatewayClient;
import org.jarvis.apigateway.proxy.DownstreamProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceProxyControllerTest {

    @Mock
    private DownstreamProxyService downstreamProxyService;

    @Mock
    private VoiceGatewayClient voiceGatewayClient;

    @InjectMocks
    private VoiceProxyController controller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "voiceGatewayUrl", "http://voice-gateway");
    }

    @Test
    void transcribeRoutesMultipartRequestsThroughVoiceGatewayClient() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "sample.wav",
                "audio/wav",
                new byte[] {1, 2, 3});
        ResponseEntity<Map<String, Object>> expected = ResponseEntity.ok(Map.of(
                "success", true,
                "text", "volume up"));
        when(voiceGatewayClient.transcribe(file, "en-US")).thenReturn(expected);

        ResponseEntity<Map<String, Object>> response = controller.transcribe(file, "en-US");

        assertEquals(expected, response);
        verify(voiceGatewayClient).transcribe(file, "en-US");
    }

    @Test
    void transcribeStreamRoutesOctetStreamRequestsThroughVoiceGatewayClient() {
        byte[] audio = new byte[] {9, 8, 7, 6};
        ResponseEntity<Map<String, Object>> expected = ResponseEntity.ok(Map.of(
                "success", true,
                "text", "hello"));
        when(voiceGatewayClient.transcribeStream(audio, "en-US")).thenReturn(expected);

        ResponseEntity<Map<String, Object>> response = controller.transcribeStream(audio, "en-US");

        assertEquals(expected, response);
        verify(voiceGatewayClient).transcribeStream(audio, "en-US");
    }

    @Test
    void otherVoiceRoutesStillUseDownstreamProxy() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/voice/diagnostics");
        ResponseEntity<byte[]> expected = ResponseEntity.ok("diagnostics".getBytes(StandardCharsets.UTF_8));
        when(downstreamProxyService.forward(request, "voice-gateway", "http://voice-gateway"))
                .thenReturn(expected);

        ResponseEntity<byte[]> response = controller.proxy(request);

        assertArrayEquals(expected.getBody(), response.getBody());
        verify(downstreamProxyService).forward(any(HttpServletRequest.class), eq("voice-gateway"), eq("http://voice-gateway"));
    }
}
