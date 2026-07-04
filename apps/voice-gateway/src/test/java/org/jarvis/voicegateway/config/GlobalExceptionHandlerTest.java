package org.jarvis.voicegateway.config;

import org.jarvis.voicegateway.exception.SttUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final WebRequest request = mock(WebRequest.class);

    @Test
    void handleSttUnavailableReturns503WithStructuredBody() {
        when(request.getDescription(false)).thenReturn("uri=/api/v1/voice/transcribe");

        ResponseEntity<Map<String, Object>> response =
                handler.handleSttUnavailable(new SttUnavailableException("model missing"), request);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("STT_UNAVAILABLE", response.getBody().get("error"));
        assertEquals("model missing", response.getBody().get("message"));
        assertEquals("/api/v1/voice/transcribe", response.getBody().get("path"));
        assertEquals("voice-gateway", response.getBody().get("service"));
    }

    @Test
    void handleIllegalArgumentReturns400WithStructuredBody() {
        when(request.getDescription(false)).thenReturn("uri=/api/v1/voice/transcribe");

        ResponseEntity<Map<String, Object>> response =
                handler.handleIllegalArgument(new IllegalArgumentException("bad wav"), request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_REQUEST", response.getBody().get("error"));
        assertEquals("bad wav", response.getBody().get("message"));
    }

    @Test
    void handleGenericExceptionReturns500WithGenericMessage() {
        when(request.getDescription(false)).thenReturn("uri=/api/v1/voice/command");

        ResponseEntity<Map<String, Object>> response =
                handler.handleGenericException(new RuntimeException("something exploded"), request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("INTERNAL_ERROR", response.getBody().get("error"));
        // The internal exception message must never leak to the client.
        assertEquals("An unexpected error occurred during voice processing", response.getBody().get("message"));
    }
}
