package org.jarvis.voicegateway.rules;

import org.jarvis.voicegateway.client.PcControlActionGateway;
import org.jarvis.voicegateway.client.PlannerActionGateway;
import org.jarvis.voicegateway.client.SmartHomeActionGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Supplements {@link VoiceCommandActionDispatcherTest} with branches it does
 * not yet exercise: the missing-action guard, SMART_HOME validation and
 * failure handling, and SMART_HOME dispatch when a real (non-blank) userId is
 * supplied instead of falling back to "local-user".
 */
@ExtendWith(MockitoExtension.class)
class VoiceCommandActionDispatcherBranchCoverageTest {

    @Mock
    private PcControlActionGateway pcControlActionGateway;
    @Mock
    private SmartHomeActionGateway smartHomeActionGateway;
    @Mock
    private PlannerActionGateway plannerActionGateway;

    @Test
    void dispatchThrowsWhenMatchedCommandHasNoAction() {
        VoiceCommandActionDispatcher dispatcher =
                new VoiceCommandActionDispatcher(pcControlActionGateway, smartHomeActionGateway, plannerActionGateway);

        VoiceCommandCatalog.Match match = matchFor(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> dispatcher.dispatch(match, "user-1", "corr-1"));
        assertEquals("Matched rule command has no action", ex.getMessage());
    }

    @Test
    void smartHomeDispatchThrowsWhenDeviceIdIsNull() {
        VoiceCommandActionDispatcher dispatcher =
                new VoiceCommandActionDispatcher(pcControlActionGateway, smartHomeActionGateway, plannerActionGateway);

        VoiceCommandCatalog.Match match = matchFor(new VoiceCommandCatalog.Action(
                VoiceCommandCatalog.ActionTarget.SMART_HOME, "TURN_ON", null, null, Map.of()));

        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(match, "user-1", "corr-1"));
    }

    @Test
    void smartHomeDispatchThrowsWhenDeviceIdIsBlank() {
        VoiceCommandActionDispatcher dispatcher =
                new VoiceCommandActionDispatcher(pcControlActionGateway, smartHomeActionGateway, plannerActionGateway);

        VoiceCommandCatalog.Match match = matchFor(new VoiceCommandCatalog.Action(
                VoiceCommandCatalog.ActionTarget.SMART_HOME, "TURN_ON", "   ", null, Map.of()));

        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(match, "user-1", "corr-1"));
    }

    @Test
    void smartHomeDispatchUsesProvidedUserIdWhenNonBlank() {
        VoiceCommandActionDispatcher dispatcher =
                new VoiceCommandActionDispatcher(pcControlActionGateway, smartHomeActionGateway, plannerActionGateway);

        VoiceCommandActionDispatcher.DispatchResult result = dispatcher.dispatch(
                matchFor(new VoiceCommandCatalog.Action(
                        VoiceCommandCatalog.ActionTarget.SMART_HOME, "TURN_ON", "kitchen_light", null, Map.of())),
                "real-user-42",
                "corr-2");

        verify(smartHomeActionGateway).execute("real-user-42", "kitchen_light", "TURN_ON", null);
        assertTrue(result.executionSucceeded());
        assertEquals("kitchen_light", result.routedParams().get("deviceId"));
    }

    @Test
    void smartHomeDispatchFallsBackToLocalUserWhenUserIdBlank() {
        VoiceCommandActionDispatcher dispatcher =
                new VoiceCommandActionDispatcher(pcControlActionGateway, smartHomeActionGateway, plannerActionGateway);

        dispatcher.dispatch(
                matchFor(new VoiceCommandCatalog.Action(
                        VoiceCommandCatalog.ActionTarget.SMART_HOME, "TURN_ON", "kitchen_light", null, Map.of())),
                "   ",
                "corr-3");

        verify(smartHomeActionGateway).execute("local-user", "kitchen_light", "TURN_ON", null);
    }

    @Test
    void smartHomeDispatchMarksFailureWhenGatewayThrows() {
        VoiceCommandActionDispatcher dispatcher =
                new VoiceCommandActionDispatcher(pcControlActionGateway, smartHomeActionGateway, plannerActionGateway);
        doThrow(new IllegalStateException("device offline"))
                .when(smartHomeActionGateway).execute("local-user", "kitchen_light", "TURN_ON", null);

        VoiceCommandActionDispatcher.DispatchResult result = dispatcher.dispatch(
                matchFor(new VoiceCommandCatalog.Action(
                        VoiceCommandCatalog.ActionTarget.SMART_HOME, "TURN_ON", "kitchen_light", null, Map.of())),
                null,
                "corr-4");

        assertTrue(result.actionResolved());
        assertTrue(result.executorFound());
        assertTrue(result.executionAttempted());
        assertFalse(result.executionSucceeded());
        assertTrue(result.executionFailed());
        assertEquals("SMART_HOME_CAPABILITY_UNAVAILABLE: device offline", result.failureReason());
    }

    private VoiceCommandCatalog.Match matchFor(VoiceCommandCatalog.Action action) {
        VoiceCommandCatalog.Command command = new VoiceCommandCatalog.Command(
                "test-command",
                "test",
                true,
                0,
                List.of(new VoiceCommandCatalog.Matcher(VoiceCommandCatalog.MatcherType.EXACT, List.of("test"))),
                action,
                null);
        Map<String, Object> params = action != null ? action.params() : Map.of();
        return new VoiceCommandCatalog.Match(command, VoiceCommandCatalog.MatcherType.EXACT, "test", params);
    }
}
