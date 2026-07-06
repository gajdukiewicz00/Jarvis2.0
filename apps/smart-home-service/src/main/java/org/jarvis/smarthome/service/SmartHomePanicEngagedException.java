package org.jarvis.smarthome.service;

/**
 * Thrown by {@code StatefulSmartHomeService#executeAction} when the shared
 * {@code org.jarvis.common.safety.SystemPanicState} kill-switch is engaged.
 *
 * <p>Every device action — including scene/group/room "apply" fan-out, which
 * all route through {@code executeAction} — must refuse to actuate while
 * panic is engaged. {@code SmartHomeController} maps this to
 * {@code HTTP 423 Locked} for the single-device action endpoint; the
 * scene/group/room fan-out endpoints catch it per-step alongside other
 * runtime failures so one blocked device does not prevent reporting results
 * for the rest of the batch.
 */
public class SmartHomePanicEngagedException extends RuntimeException {

    public SmartHomePanicEngagedException() {
        super("System panic is engaged; all device actions are blocked until it is cleared");
    }
}
