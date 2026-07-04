package org.jarvis.voicegateway.service;

import org.jarvis.voicegateway.client.PcControlActionGateway;
import org.jarvis.voicegateway.client.SmartHomeActionGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Covers LocalIntentExecutionService edge-case branches not already exercised by
 * LocalIntentExecutionServiceTest: the null-language fallback in respond(), the
 * blank-after-trim fallback in stringParam(), the blank-action-normalizes-to-"unknown"
 * branch in normalizeAction(), and the partially-missing-parameter branches of
 * executeSmartHome() (only one of deviceId/action present, rather than both absent).
 */
@ExtendWith(MockitoExtension.class)
class LocalIntentExecutionServiceGapsTest {

    @Mock
    private PcControlActionGateway pcControlActionGateway;
    @Mock
    private SmartHomeActionGateway smartHomeActionGateway;

    private LocalIntentExecutionService service;

    @BeforeEach
    void setUp() {
        service = new LocalIntentExecutionService(pcControlActionGateway, smartHomeActionGateway);
    }

    @Test
    void respondFallsBackToRussianWhenLanguageIsNull() {
        LocalIntentExecutionService.ExecutionResult result = service.execute("greeting", Map.of(), null, "corr-1", "user-1");

        assertEquals("Привет, сэр.", result.responseText());
    }

    @Test
    void normalizeActionTreatsBlankActionAsUnknown() {
        LocalIntentExecutionService.ExecutionResult result = service.execute("   ", Map.of(), "ru", "corr-1", "user-1");

        assertFalse(result.actionResolved());
        assertEquals("LOCAL_FALLBACK_UNSUPPORTED", result.failureReason());
    }

    @Test
    void stringParamFallsBackToDefaultWhenValueIsBlankAfterTrim() {
        when(pcControlActionGateway.dispatch(eq("OPEN_URL"), eq(Map.of("url", "https://radio.garden/")), any(), any()))
                .thenReturn(new PcControlActionGateway.DispatchResult("OK", true, true, true, false, null, Map.of()));

        service.execute("play_radio", Map.of("url", "   "), "ru", "corr-1", "user-1");

        org.mockito.Mockito.verify(pcControlActionGateway)
                .dispatch(eq("OPEN_URL"), eq(Map.of("url", "https://radio.garden/")), any(), any());
    }

    @Test
    void smartHomeActionFailsWhenOnlyDeviceIdIsPresent() {
        LocalIntentExecutionService.ExecutionResult result =
                service.execute("smart_home_action", Map.of("deviceId", "kitchen_light"), "ru", "corr-1", "user-1");

        assertFalse(result.executionSucceeded());
        assertEquals("SMART_HOME_PARAMETERS_INVALID", result.failureReason());
    }

    @Test
    void smartHomeActionFailsWhenOnlyActionIsPresent() {
        LocalIntentExecutionService.ExecutionResult result =
                service.execute("smart_home_action", Map.of("action", "TURN_ON"), "ru", "corr-1", "user-1");

        assertFalse(result.executionSucceeded());
        assertEquals("SMART_HOME_PARAMETERS_INVALID", result.failureReason());
    }

    @Test
    void smartHomeActionEnglishFailureMessageUsedForEnglishLanguage() {
        LocalIntentExecutionService.ExecutionResult result =
                service.execute("smart_home_action", Map.of(), "en-US", "corr-1", "user-1");

        assertEquals("Smart-home command parameters are incomplete.", result.responseText());
    }

    @Test
    void dispatchPcFailureResponseUsesEnglishTextForEnglishLanguage() {
        when(pcControlActionGateway.dispatch(eq("MUTE"), anyMap(), any(), any()))
                .thenReturn(new PcControlActionGateway.DispatchResult("ERR", true, true, false, true, "boom", Map.of()));

        LocalIntentExecutionService.ExecutionResult result = service.execute("mute", Map.of(), "en-US", "corr-1", "user-1");

        assertTrue(result.executionFailed());
        assertEquals("I couldn't execute that command locally.", result.responseText());
    }
}
