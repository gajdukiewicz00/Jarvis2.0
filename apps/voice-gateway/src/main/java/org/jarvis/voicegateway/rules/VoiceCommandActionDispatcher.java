package org.jarvis.voicegateway.rules;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.client.PcControlActionGateway;
import org.jarvis.voicegateway.client.SmartHomeActionGateway;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceCommandActionDispatcher {

    private final PcControlActionGateway pcControlActionGateway;
    private final SmartHomeActionGateway smartHomeActionGateway;

    public DispatchResult dispatch(VoiceCommandCatalog.Match match, String userId, String correlationId) {
        VoiceCommandCatalog.Action action = match.action();
        if (action == null) {
            throw new IllegalArgumentException("Matched rule command has no action");
        }

        return switch (action.target()) {
            case INTERNAL -> {
                log.info("🧠 Executing internal rule command: id={}, action={}, correlationId={}",
                        match.command().id(), action.name(), correlationId);
                yield new DispatchResult(
                        true,
                        false,
                        false,
                        false,
                        false,
                        null,
                        action.name(),
                        Map.of());
            }
            case PC_CONTROL -> {
                Map<String, Object> params = new LinkedHashMap<>(match.parameters());
                PcControlActionGateway.DispatchResult gatewayResult =
                        pcControlActionGateway.dispatch(action.name(), params, userId, correlationId);
                log.info(
                        "🎛️ Rule command routed to desktop executor: id={}, action={}, correlationId={}, executorFound={}, executionAttempted={}, executionSucceeded={}, failureReason={}",
                        match.command().id(),
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
                        Map.copyOf(params));
            }
            case SYSTEM -> {
                Map<String, Object> params = new LinkedHashMap<>(match.parameters());
                params.put("command", action.name());
                PcControlActionGateway.DispatchResult gatewayResult =
                        pcControlActionGateway.dispatch("SYSTEM_COMMAND", params, userId, correlationId);
                log.info(
                        "🖥️ Rule command routed as system command: id={}, command={}, correlationId={}, executorFound={}, executionAttempted={}, executionSucceeded={}, failureReason={}",
                        match.command().id(),
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
                        Map.copyOf(params));
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
                            match.command().id(),
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
                                    "action", action.name()));
                } catch (RuntimeException e) {
                    log.warn(
                            "🏠 Smart-home capability unavailable for rule command: id={}, deviceId={}, action={}, correlationId={}, error={}",
                            match.command().id(),
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
                                    "action", action.name()));
                }
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
            Map<String, Object> routedParams) {
    }
}
