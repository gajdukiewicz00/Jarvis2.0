package org.jarvis.orchestrator.assist;

import org.springframework.stereotype.Component;
import java.util.Locale;
import java.util.Set;

/**
 * Safety classification for proposed desktop actions. Enforced in code, not
 * just docs. Unknown action types are UNKNOWN (refused), never assumed safe.
 */
@Component
public class ActionSafetyPolicy {

    public enum SafetyClass { SAFE, GUARDED, DANGEROUS, UNKNOWN }

    private static final Set<String> SAFE = Set.of("OPEN_APP", "OPEN_URL", "FOCUS_WINDOW", "NONE");
    private static final Set<String> GUARDED = Set.of("TYPE_TEXT", "HOTKEY");
    private static final Set<String> DANGEROUS = Set.of(
            "DELETE_FILE", "SEND_MESSAGE", "SEND_EMAIL", "INSTALL_PACKAGE",
            "RUN_ARBITRARY_COMMAND", "RUN_SHELL", "COMMIT_PUSH_CODE",
            "MODIFY_SECURITY_SETTINGS", "EXPOSE_SECRET", "SHUTDOWN");

    public SafetyClass classify(String type) {
        if (type == null || type.isBlank()) return SafetyClass.UNKNOWN;
        String t = type.trim().toUpperCase(Locale.ROOT);
        if (SAFE.contains(t)) return SafetyClass.SAFE;
        if (GUARDED.contains(t)) return SafetyClass.GUARDED;
        if (DANGEROUS.contains(t)) return SafetyClass.DANGEROUS;
        return SafetyClass.UNKNOWN;
    }
}
