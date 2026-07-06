package org.jarvis.smarthome.security;

import org.jarvis.smarthome.model.SmartHomeDeviceType;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Central authority for which device types are "security-critical" and
 * therefore require an explicit confirmation flag before an action on them
 * is actually applied.
 *
 * <p>Used by {@code StatefulSmartHomeService#executeAction} (blocks
 * unconfirmed actions on real devices), {@code SmartHomeAutomationEngine}
 * (an automation rule's {@code allowSensitiveActions} flag stands in for
 * the confirmation), and the scene/automation dry-run simulators (report
 * {@code needsConfirmation} without ever actuating anything).
 */
@Component
public class SafetyPolicy {

    private static final Set<SmartHomeDeviceType> SECURITY_CRITICAL_TYPES =
            Set.of(SmartHomeDeviceType.LOCK, SmartHomeDeviceType.DOOR, SmartHomeDeviceType.GARAGE);

    /** Whether {@code type} is a security-critical device type (locks, doors, garage doors). */
    public boolean isSecurityCritical(SmartHomeDeviceType type) {
        return type != null && SECURITY_CRITICAL_TYPES.contains(type);
    }

    /** Whether an action on a device of {@code type} must be blocked given {@code confirmed}. */
    public boolean requiresConfirmation(SmartHomeDeviceType type, boolean confirmed) {
        return isSecurityCritical(type) && !confirmed;
    }
}
