package org.jarvis.voicegateway.rules;

import org.jarvis.voicegateway.client.PcControlActionGateway;
import org.jarvis.voicegateway.client.SmartHomeActionGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class VoiceCommandActionDispatcherTest {

    @Mock
    private PcControlActionGateway pcControlActionGateway;
    @Mock
    private SmartHomeActionGateway smartHomeActionGateway;

    @Test
    void dispatchesPcControlActionsWithStaticParams() {
        VoiceCommandActionDispatcher dispatcher = new VoiceCommandActionDispatcher(pcControlActionGateway, smartHomeActionGateway);

        VoiceCommandActionDispatcher.DispatchResult result = dispatcher.dispatch(
                matchFor(new VoiceCommandCatalog.Action(
                        VoiceCommandCatalog.ActionTarget.PC_CONTROL,
                        "OPEN_APP",
                        null,
                        null,
                        Map.of("app", "browser"))),
                "user-1",
                "corr-1");

        assertEquals("OPEN_APP", result.routedAction());
        verify(pcControlActionGateway).dispatch("OPEN_APP", Map.of("app", "browser"), "user-1");
    }

    @Test
    void dispatchesSystemActionsAsSystemCommand() {
        VoiceCommandActionDispatcher dispatcher = new VoiceCommandActionDispatcher(pcControlActionGateway, smartHomeActionGateway);

        VoiceCommandActionDispatcher.DispatchResult result = dispatcher.dispatch(
                matchFor(new VoiceCommandCatalog.Action(
                        VoiceCommandCatalog.ActionTarget.SYSTEM,
                        "sleep",
                        null,
                        null,
                        Map.of())),
                "user-1",
                "corr-2");

        assertEquals("SYSTEM_COMMAND", result.routedAction());
        verify(pcControlActionGateway).dispatch("SYSTEM_COMMAND", Map.of("command", "sleep"), "user-1");
    }

    @Test
    void dispatchesSmartHomeActionsWithFallbackUserScope() {
        VoiceCommandActionDispatcher dispatcher = new VoiceCommandActionDispatcher(pcControlActionGateway, smartHomeActionGateway);

        dispatcher.dispatch(
                matchFor(new VoiceCommandCatalog.Action(
                        VoiceCommandCatalog.ActionTarget.SMART_HOME,
                        "TURN_ON",
                        "kitchen_light",
                        null,
                        Map.of())),
                null,
                "corr-3");

        verify(smartHomeActionGateway).execute("local-user", "kitchen_light", "TURN_ON", null);
    }

    @Test
    void internalActionsDoNotCallExternalGateways() {
        VoiceCommandActionDispatcher dispatcher = new VoiceCommandActionDispatcher(pcControlActionGateway, smartHomeActionGateway);

        dispatcher.dispatch(
                matchFor(new VoiceCommandCatalog.Action(
                        VoiceCommandCatalog.ActionTarget.INTERNAL,
                        "WAKE_RESPONSE",
                        null,
                        null,
                        Map.of())),
                "user-1",
                "corr-4");

        verify(pcControlActionGateway, never()).dispatch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any());
        verify(smartHomeActionGateway, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private VoiceCommandCatalog.Match matchFor(VoiceCommandCatalog.Action action) {
        VoiceCommandCatalog.Command command = new VoiceCommandCatalog.Command(
                "test-command",
                "test",
                true,
                0,
                java.util.List.of(new VoiceCommandCatalog.Matcher(VoiceCommandCatalog.MatcherType.EXACT, java.util.List.of("test"))),
                action,
                new VoiceCommandCatalog.Response("yes_sir", Map.of()));
        return new VoiceCommandCatalog.Match(command, VoiceCommandCatalog.MatcherType.EXACT, "test", action.params());
    }
}
