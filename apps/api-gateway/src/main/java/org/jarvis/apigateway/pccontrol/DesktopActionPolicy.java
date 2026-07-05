package org.jarvis.apigateway.pccontrol;

import java.util.Locale;
import java.util.Set;

/**
 * Server-side allowlist for the api-gateway's official desktop-action route
 * ({@code /api/v1/pc/desktop/action}, Roadmap #1.8/#8).
 *
 * <p>This is deliberately a narrower, independent policy from pc-control's
 * own {@code CommandValidator} (which governs the broader {@code /api/v1/pc/action}
 * action set: media/volume/system-command/scenario/etc.). The desktop route
 * only ever forwards the five actions Roadmap #8 calls out — everything else
 * is refused here, before any downstream call is made, mirroring
 * {@code classify_action} in scripts/jarvis-host-bridge.sh.</p>
 */
public final class DesktopActionPolicy {

    private static final Set<String> SAFE_ACTIONS = Set.of(
            "OPEN_APP", "OPEN_URL", "WINDOW_FOCUS", "SCREENSHOT");

    private static final Set<String> GUARDED_ACTIONS = Set.of(
            "TYPE_TEXT", "HOTKEY");

    private static final Set<String> DANGEROUS_ACTIONS = Set.of(
            "DELETE_FILE",
            "SEND_MESSAGE",
            "SEND_EMAIL",
            "INSTALL_PACKAGE",
            "RUN_ARBITRARY_COMMAND",
            "RUN_SHELL",
            "SYSTEM_COMMAND",
            "COMMIT_PUSH_CODE",
            "MODIFY_SECURITY_SETTINGS",
            "EXPOSE_SECRET",
            "SHUTDOWN");

    private DesktopActionPolicy() {
    }

    /** Classify a (possibly raw-cased) action type. Never throws. */
    public static DesktopActionClass classify(String rawActionType) {
        String type = normalize(rawActionType);
        if (type == null) {
            return DesktopActionClass.UNKNOWN;
        }
        if (SAFE_ACTIONS.contains(type)) {
            return DesktopActionClass.SAFE;
        }
        if (GUARDED_ACTIONS.contains(type)) {
            return DesktopActionClass.GUARDED;
        }
        if (DANGEROUS_ACTIONS.contains(type)) {
            return DesktopActionClass.DANGEROUS;
        }
        return DesktopActionClass.UNKNOWN;
    }

    /** Normalize an action type to the canonical UPPER_SNAKE_CASE form, or null when blank. */
    public static String normalize(String rawActionType) {
        if (rawActionType == null || rawActionType.isBlank()) {
            return null;
        }
        return rawActionType.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }
}
