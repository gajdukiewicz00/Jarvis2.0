package org.jarvis.smarthome.service.impl;

import lombok.RequiredArgsConstructor;
import org.jarvis.smarthome.model.SmartHomeActionRequest;
import org.jarvis.smarthome.model.SmartHomeActionResult;
import org.jarvis.smarthome.model.SmartHomeDeviceDefinition;
import org.jarvis.smarthome.model.SmartHomeDeviceView;
import org.jarvis.smarthome.security.ActionValidator;
import org.jarvis.smarthome.service.SmartHomeCommandTransport;
import org.jarvis.smarthome.service.SmartHomeDeviceCatalog;
import org.jarvis.smarthome.service.SmartHomeDeviceNotFoundException;
import org.jarvis.smarthome.service.SmartHomeService;
import org.jarvis.smarthome.service.SmartHomeValidationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class StatefulSmartHomeService implements SmartHomeService {

    private final ActionValidator actionValidator;
    private final SmartHomeDeviceCatalog catalog;
    private final SmartHomeCommandTransport commandTransport;
    private final Clock clock;

    private final Map<String, StoredDeviceState> stateStore = new ConcurrentHashMap<>();

    @Override
    public List<SmartHomeDeviceView> listDevices(String userId) {
        String scopedUserId = requireUserId(userId);
        return catalog.all().stream()
                .map(device -> toView(scopedUserId, device))
                .toList();
    }

    @Override
    public SmartHomeDeviceView getDevice(String userId, String deviceId) {
        String scopedUserId = requireUserId(userId);
        SmartHomeDeviceDefinition device = catalog.findById(deviceId)
                .orElseThrow(() -> new SmartHomeDeviceNotFoundException(deviceId));
        return toView(scopedUserId, device);
    }

    @Override
    public SmartHomeActionResult executeAction(String userId, String deviceId, SmartHomeActionRequest request) {
        if (request == null || request.action() == null || request.action().isBlank()) {
            throw new SmartHomeValidationException("Action is required");
        }

        String scopedUserId = requireUserId(userId);
        String normalizedAction = normalizeAction(request.action());
        actionValidator.validateAction(normalizedAction);

        SmartHomeDeviceDefinition device = catalog.findById(deviceId)
                .orElseThrow(() -> new SmartHomeDeviceNotFoundException(deviceId));
        if (!device.supportedActions().contains(normalizedAction)) {
            throw new SmartHomeValidationException(
                    "Action " + normalizedAction + " is not supported for device " + deviceId);
        }

        commandTransport.dispatch(scopedUserId, device, new SmartHomeActionRequest(normalizedAction, request.payload()));
        StoredDeviceState stored = getOrCreateState(scopedUserId, device);
        synchronized (stored) {
            applyAction(device, stored.state, normalizedAction, request.payload());
            stored.updatedAt = clock.instant();
        }

        SmartHomeDeviceView updatedDevice = toView(scopedUserId, device);
        String message = commandTransport.providerName().equals("mock")
                ? "Action executed locally"
                : "Action dispatched via MQTT and local state updated";
        return new SmartHomeActionResult(
                true,
                scopedUserId,
                normalizedAction,
                message,
                updatedDevice,
                clock.instant());
    }

    @Override
    public List<String> supportedActions() {
        return catalog.supportedActions();
    }

    private SmartHomeDeviceView toView(String userId, SmartHomeDeviceDefinition device) {
        StoredDeviceState stored = getOrCreateState(userId, device);
        synchronized (stored) {
            return new SmartHomeDeviceView(
                    device.id(),
                    device.displayName(),
                    device.room(),
                    device.type(),
                    device.supportedActions(),
                    Map.copyOf(stored.state),
                    commandTransport.providerName(),
                    stored.updatedAt);
        }
    }

    private StoredDeviceState getOrCreateState(String userId, SmartHomeDeviceDefinition device) {
        return stateStore.computeIfAbsent(userId + "::" + device.id(),
                ignored -> new StoredDeviceState(new LinkedHashMap<>(device.defaultState()), clock.instant()));
    }

    private void applyAction(
            SmartHomeDeviceDefinition device,
            Map<String, Object> state,
            String action,
            String payload) {
        switch (device.type()) {
            case LIGHT -> applyLightAction(state, action, payload);
            case THERMOSTAT -> applyThermostatAction(state, action, payload);
            case LOCK -> applyLockAction(state, action);
            case SWITCH -> applySwitchAction(state, action);
            case TEMPERATURE_SENSOR, HUMIDITY_SENSOR, MOTION_SENSOR, DOOR_SENSOR -> throw new SmartHomeValidationException(
                    "Sensors are read-only; report readings via the sensor-ingestion endpoint instead of an action");
        }
    }

    private void applyLightAction(Map<String, Object> state, String action, String payload) {
        boolean currentPower = (Boolean) state.getOrDefault("power", false);
        int brightness = ((Number) state.getOrDefault("brightness", 50)).intValue();
        switch (action) {
            case "TURN_ON" -> state.put("power", true);
            case "TURN_OFF" -> state.put("power", false);
            case "TOGGLE" -> state.put("power", !currentPower);
            case "DIM" -> {
                int nextBrightness = Math.max(0, brightness - 10);
                state.put("brightness", nextBrightness);
                state.put("power", nextBrightness > 0);
            }
            case "BRIGHTEN" -> {
                int nextBrightness = Math.min(100, brightness + 10);
                state.put("brightness", nextBrightness);
                state.put("power", true);
            }
            case "SET_BRIGHTNESS" -> {
                int nextBrightness = parseIntegerPayload(payload, 0, 100, "brightness");
                state.put("brightness", nextBrightness);
                state.put("power", nextBrightness > 0);
            }
            case "SET_COLOR" -> {
                if (payload == null || payload.isBlank()) {
                    throw new SmartHomeValidationException("Color payload is required");
                }
                state.put("color", payload.trim());
                state.put("power", true);
            }
            default -> throw new SmartHomeValidationException("Unsupported light action: " + action);
        }
    }

    private void applyThermostatAction(Map<String, Object> state, String action, String payload) {
        switch (action) {
            case "TURN_ON" -> state.put("power", true);
            case "TURN_OFF" -> state.put("power", false);
            case "SET_TEMPERATURE" -> {
                double nextTemperature = parseDoublePayload(payload, 16.0, 30.0, "temperature");
                state.put("targetTemperature", nextTemperature);
                state.put("power", true);
            }
            default -> throw new SmartHomeValidationException("Unsupported thermostat action: " + action);
        }
    }

    private void applyLockAction(Map<String, Object> state, String action) {
        switch (action) {
            case "LOCK" -> state.put("locked", true);
            case "UNLOCK" -> state.put("locked", false);
            default -> throw new SmartHomeValidationException("Unsupported lock action: " + action);
        }
    }

    private void applySwitchAction(Map<String, Object> state, String action) {
        boolean currentPower = (Boolean) state.getOrDefault("power", false);
        switch (action) {
            case "TURN_ON" -> state.put("power", true);
            case "TURN_OFF" -> state.put("power", false);
            case "TOGGLE" -> state.put("power", !currentPower);
            default -> throw new SmartHomeValidationException("Unsupported switch action: " + action);
        }
    }

    private int parseIntegerPayload(String payload, int min, int max, String field) {
        try {
            int value = Integer.parseInt(payload == null ? "" : payload.trim());
            if (value < min || value > max) {
                throw new SmartHomeValidationException(field + " must be between " + min + " and " + max);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new SmartHomeValidationException(field + " payload must be numeric");
        }
    }

    private double parseDoublePayload(String payload, double min, double max, String field) {
        try {
            double value = Double.parseDouble(payload == null ? "" : payload.trim());
            if (value < min || value > max) {
                throw new SmartHomeValidationException(field + " must be between " + min + " and " + max);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new SmartHomeValidationException(field + " payload must be numeric");
        }
    }

    private static String requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new SmartHomeValidationException("Delegated user context is required");
        }
        return userId.trim();
    }

    private static String normalizeAction(String action) {
        return action.trim().replace('-', '_').toUpperCase();
    }

    private static final class StoredDeviceState {
        private final Map<String, Object> state;
        private Instant updatedAt;

        private StoredDeviceState(Map<String, Object> state, Instant updatedAt) {
            this.state = state;
            this.updatedAt = updatedAt;
        }
    }
}
