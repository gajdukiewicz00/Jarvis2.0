package org.jarvis.voicegateway.rules;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.client.FinanceActionGateway;
import org.jarvis.voicegateway.client.PcControlActionGateway;
import org.jarvis.voicegateway.client.PlannerActionGateway;
import org.jarvis.voicegateway.client.SmartHomeActionGateway;
import org.jarvis.voicegateway.client.VisionActionGateway;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceCommandActionDispatcher {

    private final PcControlActionGateway pcControlActionGateway;
    private final SmartHomeActionGateway smartHomeActionGateway;
    private final PlannerActionGateway plannerActionGateway;
    private final FinanceActionGateway financeActionGateway;
    private final VisionActionGateway visionActionGateway;

    /**
     * Dispatches a matched rule command. Delegates to the shared {@link #dispatchAction} core using
     * the match's resolved parameters and command id.
     */
    public DispatchResult dispatch(VoiceCommandCatalog.Match match, String userId, String correlationId) {
        VoiceCommandCatalog.Action action = match.action();
        if (action == null) {
            throw new IllegalArgumentException("Matched rule command has no action");
        }
        String commandId = match.command() != null ? match.command().id() : "unknown";
        return dispatchAction(action, match.parameters(), commandId, userId, correlationId);
    }

    /**
     * Dispatches a synthetic action that did not come from rule matching — used by the confirmation
     * path to execute a captured action (e.g. VISION) reusing the exact same override / failure
     * logic as normal commands. Parameters are taken from {@link VoiceCommandCatalog.Action#params()}.
     */
    public DispatchResult dispatch(VoiceCommandCatalog.Action action, String userId, String correlationId) {
        if (action == null) {
            throw new IllegalArgumentException("Cannot dispatch a null action");
        }
        return dispatchAction(action, action.params(), "confirm-execute", userId, correlationId);
    }

    private DispatchResult dispatchAction(
            VoiceCommandCatalog.Action action,
            Map<String, Object> matchedParameters,
            String commandId,
            String userId,
            String correlationId) {

        return switch (action.target()) {
            case INTERNAL -> {
                log.info("🧠 Executing internal rule command: id={}, action={}, correlationId={}",
                        commandId, action.name(), correlationId);
                yield new DispatchResult(
                        true,
                        false,
                        false,
                        false,
                        false,
                        null,
                        action.name(),
                        Map.of(),
                        null);
            }
            case PC_CONTROL -> {
                Map<String, Object> params = new LinkedHashMap<>(matchedParameters);
                PcControlActionGateway.DispatchResult gatewayResult =
                        pcControlActionGateway.dispatch(action.name(), params, userId, correlationId);
                log.info(
                        "🎛️ Rule command routed to desktop executor: id={}, action={}, correlationId={}, executorFound={}, executionAttempted={}, executionSucceeded={}, failureReason={}",
                        commandId,
                        action.name(),
                        correlationId,
                        gatewayResult.executorFound(),
                        gatewayResult.executionAttempted(),
                        gatewayResult.executionSucceeded(),
                        gatewayResult.failureReason());
                yield new DispatchResult(
                        true,
                        gatewayResult.executorFound(),
                        gatewayResult.executionAttempted(),
                        gatewayResult.executionSucceeded(),
                        gatewayResult.executionFailed(),
                        gatewayResult.failureReason(),
                        action.name(),
                        Map.copyOf(params),
                        null);
            }
            case SYSTEM -> {
                Map<String, Object> params = new LinkedHashMap<>(matchedParameters);
                params.put("command", action.name());
                PcControlActionGateway.DispatchResult gatewayResult =
                        pcControlActionGateway.dispatch("SYSTEM_COMMAND", params, userId, correlationId);
                log.info(
                        "🖥️ Rule command routed as system command: id={}, command={}, correlationId={}, executorFound={}, executionAttempted={}, executionSucceeded={}, failureReason={}",
                        commandId,
                        action.name(),
                        correlationId,
                        gatewayResult.executorFound(),
                        gatewayResult.executionAttempted(),
                        gatewayResult.executionSucceeded(),
                        gatewayResult.failureReason());
                yield new DispatchResult(
                        true,
                        gatewayResult.executorFound(),
                        gatewayResult.executionAttempted(),
                        gatewayResult.executionSucceeded(),
                        gatewayResult.executionFailed(),
                        gatewayResult.failureReason(),
                        "SYSTEM_COMMAND",
                        Map.copyOf(params),
                        null);
            }
            case SMART_HOME -> {
                String scopedUserId = userId != null && !userId.isBlank() ? userId : "local-user";
                if (action.deviceId() == null || action.deviceId().isBlank()) {
                    throw new IllegalArgumentException("SMART_HOME rule command requires deviceId");
                }
                try {
                    smartHomeActionGateway.execute(scopedUserId, action.deviceId(), action.name(), action.payload());
                    log.info(
                            "🏠 Rule command routed to smart-home executor: id={}, deviceId={}, action={}, correlationId={}, userId={}",
                            commandId,
                            action.deviceId(),
                            action.name(),
                            correlationId,
                            scopedUserId);
                    yield new DispatchResult(
                            true,
                            true,
                            true,
                            true,
                            false,
                            null,
                            action.name(),
                            Map.of(
                                    "deviceId", action.deviceId(),
                                    "action", action.name()),
                            null);
                } catch (RuntimeException e) {
                    log.warn(
                            "🏠 Smart-home capability unavailable for rule command: id={}, deviceId={}, action={}, correlationId={}, error={}",
                            commandId,
                            action.deviceId(),
                            action.name(),
                            correlationId,
                            e.getMessage());
                    yield new DispatchResult(
                            true,
                            true,
                            true,
                            false,
                            true,
                            "SMART_HOME_CAPABILITY_UNAVAILABLE: " + e.getMessage(),
                            action.name(),
                            Map.of(
                                    "deviceId", action.deviceId(),
                                    "action", action.name()),
                            null);
                }
            }
            case PLANNER -> {
                String scopedUserId = userId != null && !userId.isBlank() ? userId : "local-user";
                // Voice + desktop default to ru-RU; gateway still honours en-* if provided.
                PlannerActionGateway.PlannerResult plannerResult =
                        plannerActionGateway.summarizeDay(scopedUserId, "ru-RU", action.name());
                log.info(
                        "🗓️ Rule command routed to planner: id={}, action={}, correlationId={}, userId={}, success={}",
                        commandId,
                        action.name(),
                        correlationId,
                        scopedUserId,
                        plannerResult.success());
                if (plannerResult.success()) {
                    yield new DispatchResult(
                            true,
                            true,
                            true,
                            true,
                            false,
                            null,
                            action.name(),
                            Map.of("summary", plannerResult.spokenSummary()),
                            plannerResult.spokenSummary());
                }
                yield new DispatchResult(
                        true,
                        true,
                        true,
                        false,
                        true,
                        plannerResult.failureReason(),
                        action.name(),
                        Map.of(),
                        null);
            }
            case FINANCE -> {
                String scopedUserId = userId != null && !userId.isBlank() ? userId : "local-user";
                FinanceActionGateway.FinanceResult financeResult =
                        financeActionGateway.summarize(scopedUserId, "ru-RU", action.name());
                log.info(
                        "💰 Rule command routed to finance: id={}, action={}, correlationId={}, userId={}, success={}",
                        commandId, action.name(), correlationId, scopedUserId, financeResult.success());
                if (financeResult.success()) {
                    yield new DispatchResult(
                            true, true, true, true, false, null, action.name(),
                            Map.of("summary", financeResult.spokenSummary()), financeResult.spokenSummary());
                }
                yield new DispatchResult(
                        true, true, true, false, true, financeResult.failureReason(), action.name(), Map.of(), null);
            }
            case VISION -> {
                Map<String, Object> params = new LinkedHashMap<>(matchedParameters);
                Object questionValue = params.get("question");
                String question = questionValue != null ? String.valueOf(questionValue) : "";
                DispatchResult gatewayResult = visionActionGateway.askScreen(userId, question, correlationId);
                log.info(
                        "👁️ Rule command routed to vision: id={}, action={}, correlationId={}, executionSucceeded={}, failureReason={}",
                        commandId, action.name(), correlationId,
                        gatewayResult.executionSucceeded(), gatewayResult.failureReason());
                // Re-wrap so routedAction/routedParams reflect this action while reusing the
                // gateway's execution flags, spoken answer (override) and coded failureReason.
                yield new DispatchResult(
                        gatewayResult.actionResolved(),
                        gatewayResult.executorFound(),
                        gatewayResult.executionAttempted(),
                        gatewayResult.executionSucceeded(),
                        gatewayResult.executionFailed(),
                        gatewayResult.failureReason(),
                        action.name(),
                        Map.copyOf(params),
                        gatewayResult.responseTextOverride());
            }
        };
    }

    public record DispatchResult(
            boolean actionResolved,
            boolean executorFound,
            boolean executionAttempted,
            boolean executionSucceeded,
            boolean executionFailed,
            String failureReason,
            String routedAction,
            Map<String, Object> routedParams,
            String responseTextOverride) {
    }
}
