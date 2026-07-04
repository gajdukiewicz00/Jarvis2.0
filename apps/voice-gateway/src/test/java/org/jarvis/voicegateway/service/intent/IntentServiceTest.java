package org.jarvis.voicegateway.service.intent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentServiceTest {

    private final IntentRequest request = IntentRequest.builder().text("громче").build();

    @Test
    void returnsResultFromFirstHandlerThatHandlesRequest() {
        IntentHandler first = mock(IntentHandler.class);
        IntentHandler second = mock(IntentHandler.class);
        IntentResult handledResult = IntentResult.builder().handled(true).action("VOLUME_UP").build();
        when(first.canHandle(request)).thenReturn(true);
        when(first.handle(request)).thenReturn(handledResult);

        IntentService service = new IntentService(List.of(first, second));

        IntentResult result = service.handle(request);

        assertEquals(handledResult, result);
        verify(second, never()).canHandle(any());
    }

    @Test
    void skipsHandlersThatCannotHandleRequest() {
        IntentHandler first = mock(IntentHandler.class);
        IntentHandler second = mock(IntentHandler.class);
        IntentResult handledResult = IntentResult.builder().handled(true).action("MUTE").build();
        when(first.canHandle(request)).thenReturn(false);
        when(second.canHandle(request)).thenReturn(true);
        when(second.handle(request)).thenReturn(handledResult);

        IntentService service = new IntentService(List.of(first, second));

        IntentResult result = service.handle(request);

        assertEquals(handledResult, result);
    }

    @Test
    void fallsBackToLastUnhandledResultWhenNoHandlerFullyHandlesRequest() {
        IntentHandler first = mock(IntentHandler.class);
        IntentResult unhandled = IntentResult.builder().handled(false).action("PARTIAL").build();
        when(first.canHandle(request)).thenReturn(true);
        when(first.handle(request)).thenReturn(unhandled);

        IntentService service = new IntentService(List.of(first));

        IntentResult result = service.handle(request);

        assertEquals(unhandled, result);
        assertFalse(result.isHandled());
    }

    @Test
    void returnsUnknownFallbackWhenNoHandlerCanHandleRequest() {
        IntentHandler first = mock(IntentHandler.class);
        when(first.canHandle(request)).thenReturn(false);

        IntentService service = new IntentService(List.of(first));

        IntentResult result = service.handle(request);

        assertFalse(result.isHandled());
        assertEquals("UNKNOWN", result.getAction());
        assertTrue(result.getResponse().contains("did not understand"));
    }

    @Test
    void returnsUnknownFallbackWhenHandlersListIsEmpty() {
        IntentService service = new IntentService(List.of());

        IntentResult result = service.handle(request);

        assertFalse(result.isHandled());
        assertEquals("UNKNOWN", result.getAction());
    }
}
