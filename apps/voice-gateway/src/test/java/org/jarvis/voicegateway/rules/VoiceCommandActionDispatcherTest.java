package org.jarvis.voicegateway.rules;

import org.jarvis.voicegateway.client.PcControlActionGateway;
import org.jarvis.voicegateway.client.PlannerActionGateway;
import org.jarvis.voicegateway.client.SmartHomeActionGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceCommandActionDispatcherTest {

    @Mock
    private PcControlActionGateway pcControlActionGateway;
    @Mock
    private SmartHomeActionGateway smartHomeActionGateway;
    @Mock
    private PlannerActionGateway plannerActionGateway;
    @Mock
    private org.jarvis.voicegateway.client.FinanceActionGateway financeActionGateway;
    @Mock
    private org.jarvis.voicegateway.client.VisionActionGateway visionActionGateway;

    @Test
    void dispatchesPcControlActionsWithStaticParams() {
        VoiceCommandActionDispatcher dispatcher = new VoiceCommandActionDispatcher(pcControlActionGateway, smartHomeActionGateway, plannerActionGateway, financeActionGateway, visionActionGateway);
        when(pcControlActionGateway.dispatch("OPEN_APP", Map.of("app", "browser"), "user-1", "corr-1"))
                .thenReturn(new PcControlActionGateway.DispatchResult(
                        "executed",
                        true,
                        true,
                        true,
                        false,
                        null,
                        Map.of("status", "executed")));

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
        assertTrue(result.executionSucceeded());
        verify(pcControlActionGateway).dispatch("OPEN_APP", Map.of("app", "browser"), "user-1", "corr-1");
    }

    @Test
    void dispatchesSystemActionsAsSystemCommand() {
        VoiceCommandActionDispatcher dispatcher = new VoiceCommandActionDispatcher(pcControlActionGateway, smartHomeActionGateway, plannerActionGateway, financeActionGateway, visionActionGateway);
        when(pcControlActionGateway.dispatch("SYSTEM_COMMAND", Map.of("command", "sleep"), "user-1", "corr-2"))
                .thenReturn(new PcControlActionGateway.DispatchResult(
                        "executed",
                        true,
                        true,
                        true,
                        false,
                        null,
                        Map.of("status", "executed")));

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
        assertTrue(result.executionSucceeded());
        verify(pcControlActionGateway).dispatch("SYSTEM_COMMAND", Map.of("command", "sleep"), "user-1", "corr-2");
    }

    @Test
    void dispatchesSmartHomeActionsWithFallbackUserScope() {
        VoiceCommandActionDispatcher dispatcher = new VoiceCommandActionDispatcher(pcControlActionGateway, smartHomeActionGateway, plannerActionGateway, financeActionGateway, visionActionGateway);

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
        VoiceCommandActionDispatcher dispatcher = new VoiceCommandActionDispatcher(pcControlActionGateway, smartHomeActionGateway, plannerActionGateway, financeActionGateway, visionActionGateway);

        VoiceCommandActionDispatcher.DispatchResult result = dispatcher.dispatch(
                matchFor(new VoiceCommandCatalog.Action(
                        VoiceCommandCatalog.ActionTarget.INTERNAL,
                        "WAKE_RESPONSE",
                        null,
                        null,
                        Map.of())),
                "user-1",
                "corr-4");

        assertTrue(result.actionResolved());
        assertFalse(result.executionAttempted());
        verify(pcControlActionGateway, never()).dispatch(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(smartHomeActionGateway, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dispatchMarksRuleAsFailedWhenDesktopExecutionFailsBeforeAttempt() {
        VoiceCommandActionDispatcher dispatcher = new VoiceCommandActionDispatcher(pcControlActionGateway, smartHomeActionGateway, plannerActionGateway, financeActionGateway, visionActionGateway);
        when(pcControlActionGateway.dispatch("OPEN_APP", Map.of("app", "browser"), "user-1", "corr-5"))
                .thenReturn(new PcControlActionGateway.DispatchResult(
                        "no_clients",
                        false,
                        false,
                        false,
                        true,
                        "No desktop executor is connected",
                        Map.of("status", "no_clients")));

        VoiceCommandActionDispatcher.DispatchResult result = dispatcher.dispatch(
                matchFor(new VoiceCommandCatalog.Action(
                        VoiceCommandCatalog.ActionTarget.PC_CONTROL,
                        "OPEN_APP",
                        null,
                        null,
                        Map.of("app", "browser"))),
                "user-1",
                "corr-5");

        assertTrue(result.actionResolved());
        assertFalse(result.executorFound());
        assertFalse(result.executionAttempted());
        assertTrue(result.executionFailed());
        assertEquals("No desktop executor is connected", result.failureReason());
    }

    @Test
    void dispatchesPlannerAndSurfacesSpokenSummaryAsResponseOverride() {
        VoiceCommandActionDispatcher dispatcher = new VoiceCommandActionDispatcher(pcControlActionGateway, smartHomeActionGateway, plannerActionGateway, financeActionGateway, visionActionGateway);
        when(plannerActionGateway.summarizeDay("user-1", "ru-RU", "PLANNER_TODAY"))
                .thenReturn(new PlannerActionGateway.PlannerResult(
                        true, "Сэр, сегодня у вас 4 задачи. Главный фокус: отчёт.", null));

        VoiceCommandActionDispatcher.DispatchResult result = dispatcher.dispatch(
                matchFor(new VoiceCommandCatalog.Action(
                        VoiceCommandCatalog.ActionTarget.PLANNER,
                        "PLANNER_TODAY",
                        null,
                        null,
                        Map.of())),
                "user-1",
                "corr-planner");

        assertTrue(result.actionResolved());
        assertTrue(result.executionSucceeded());
        assertFalse(result.executionFailed());
        assertEquals("PLANNER_TODAY", result.routedAction());
        assertEquals("Сэр, сегодня у вас 4 задачи. Главный фокус: отчёт.", result.responseTextOverride());
        verify(pcControlActionGateway, never()).dispatch(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dispatchesFinanceAndSurfacesSpokenSummaryAsResponseOverride() {
        VoiceCommandActionDispatcher dispatcher = new VoiceCommandActionDispatcher(pcControlActionGateway, smartHomeActionGateway, plannerActionGateway, financeActionGateway, visionActionGateway);
        when(financeActionGateway.summarize("user-1", "ru-RU", "FINANCE_SUMMARY"))
                .thenReturn(new org.jarvis.voicegateway.client.FinanceActionGateway.FinanceResult(
                        true, "Сэр, за месяц 12000 RUB. Основные категории: продукты.", null));

        VoiceCommandActionDispatcher.DispatchResult result = dispatcher.dispatch(
                matchFor(new VoiceCommandCatalog.Action(
                        VoiceCommandCatalog.ActionTarget.FINANCE, "FINANCE_SUMMARY", null, null, Map.of())),
                "user-1", "corr-fin");

        assertTrue(result.executionSucceeded());
        assertEquals("FINANCE_SUMMARY", result.routedAction());
        assertEquals("Сэр, за месяц 12000 RUB. Основные категории: продукты.", result.responseTextOverride());
    }

    @Test
    void dispatchMarksFinanceAsFailedWhenServiceUnavailable() {
        VoiceCommandActionDispatcher dispatcher = new VoiceCommandActionDispatcher(pcControlActionGateway, smartHomeActionGateway, plannerActionGateway, financeActionGateway, visionActionGateway);
        when(financeActionGateway.summarize("user-1", "ru-RU", "FINANCE_SUMMARY"))
                .thenReturn(new org.jarvis.voicegateway.client.FinanceActionGateway.FinanceResult(
                        false, null, "FINANCE_UNAVAILABLE: connection refused"));

        VoiceCommandActionDispatcher.DispatchResult result = dispatcher.dispatch(
                matchFor(new VoiceCommandCatalog.Action(
                        VoiceCommandCatalog.ActionTarget.FINANCE, "FINANCE_SUMMARY", null, null, Map.of())),
                "user-1", "corr-fin-fail");

        assertTrue(result.executionFailed());
        assertEquals("FINANCE_UNAVAILABLE: connection refused", result.failureReason());
    }

    @Test
    void dispatchMarksPlannerAsFailedWhenServiceUnavailable() {
        VoiceCommandActionDispatcher dispatcher = new VoiceCommandActionDispatcher(pcControlActionGateway, smartHomeActionGateway, plannerActionGateway, financeActionGateway, visionActionGateway);
        when(plannerActionGateway.summarizeDay("user-1", "ru-RU", "PLANNER_TODAY"))
                .thenReturn(new PlannerActionGateway.PlannerResult(
                        false, null, "PLANNER_UNAVAILABLE: connection refused"));

        VoiceCommandActionDispatcher.DispatchResult result = dispatcher.dispatch(
                matchFor(new VoiceCommandCatalog.Action(
                        VoiceCommandCatalog.ActionTarget.PLANNER,
                        "PLANNER_TODAY",
                        null,
                        null,
                        Map.of())),
                "user-1",
                "corr-planner-fail");

        assertTrue(result.actionResolved());
        assertFalse(result.executionSucceeded());
        assertTrue(result.executionFailed());
        assertEquals("PLANNER_UNAVAILABLE: connection refused", result.failureReason());
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
