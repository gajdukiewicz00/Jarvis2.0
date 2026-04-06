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
                yield new DispatchResult(true, action.name(), Map.of());
            }
            case PC_CONTROL -> {
                Map<String, Object> params = new LinkedHashMap<>(match.parameters());
                pcControlActionGateway.dispatch(action.name(), params, userId);
                yield new DispatchResult(true, action.name(), Map.copyOf(params));
            }
            case SYSTEM -> {
                Map<String, Object> params = new LinkedHashMap<>(match.parameters());
                params.put("command", action.name());
                pcControlActionGateway.dispatch("SYSTEM_COMMAND", params, userId);
                yield new DispatchResult(true, "SYSTEM_COMMAND", Map.copyOf(params));
            }
            case SMART_HOME -> {
                String scopedUserId = userId != null && !userId.isBlank() ? userId : "local-user";
                if (action.deviceId() == null || action.deviceId().isBlank()) {
                    throw new IllegalArgumentException("SMART_HOME rule command requires deviceId");
                }
                smartHomeActionGateway.execute(scopedUserId, action.deviceId(), action.name(), action.payload());
                yield new DispatchResult(true, action.name(), Map.of(
                        "deviceId", action.deviceId(),
                        "action", action.name()));
            }
        };
    }

    public record DispatchResult(boolean executed, String routedAction, Map<String, Object> routedParams) {
    }
}
