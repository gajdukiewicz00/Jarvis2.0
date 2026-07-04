package org.jarvis.common.safety;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared per-tool / per-intent permission catalog + grant policy (EPIC 3).
 * Maps each agent tool name AND each voice/orchestrator intent to the
 * {@link ToolPermission}s it requires, then checks them against the granted
 * set (conservative by default; overridable via
 * {@code jarvis.tools.granted-permissions}).
 *
 * <p>The SAME instance is reused at every entry point — the gateway tool
 * executor, the orchestrator command publisher, and the voice fast-path — so a
 * capability denied in one place is denied everywhere.</p>
 */
@Slf4j
public class ToolPermissionPolicy {

    /** tool name -> required permissions. Absent tool requires nothing. */
    private static final Map<String, Set<ToolPermission>> TOOL_CATALOG = Map.ofEntries(
            Map.entry("create_todo", EnumSet.of(ToolPermission.PLANNER_ACCESS)),
            Map.entry("update_todo", EnumSet.of(ToolPermission.PLANNER_ACCESS)),
            Map.entry("complete_todo", EnumSet.of(ToolPermission.PLANNER_ACCESS)),
            Map.entry("list_todos", EnumSet.of(ToolPermission.PLANNER_ACCESS)),
            Map.entry("create_event", EnumSet.of(ToolPermission.CALENDAR_ACCESS)),
            Map.entry("move_event", EnumSet.of(ToolPermission.CALENDAR_ACCESS)),
            Map.entry("list_events", EnumSet.of(ToolPermission.CALENDAR_ACCESS)),
            Map.entry("find_free_slot", EnumSet.of(ToolPermission.CALENDAR_ACCESS)),
            Map.entry("memory_search", EnumSet.of(ToolPermission.MEMORY_ACCESS)),
            Map.entry("finance_transactions", EnumSet.of(ToolPermission.FINANCE_ACCESS)),
            Map.entry("finance_summary", EnumSet.of(ToolPermission.FINANCE_ACCESS)),
            Map.entry("finance_analysis", EnumSet.of(ToolPermission.FINANCE_ACCESS)),
            Map.entry("finance_budget_status", EnumSet.of(ToolPermission.FINANCE_ACCESS)));

    private static final Set<ToolPermission> DEFAULT_GRANTS = EnumSet.of(
            ToolPermission.PLANNER_ACCESS,
            ToolPermission.CALENDAR_ACCESS,
            ToolPermission.MEMORY_ACCESS,
            ToolPermission.SMART_HOME_ACCESS,
            ToolPermission.PC_CONTROL,
            ToolPermission.NOTIFICATION_ACCESS,
            ToolPermission.READ_FILES,
            ToolPermission.NETWORK_ACCESS);

    private final Set<ToolPermission> granted;

    public ToolPermissionPolicy(String grantedCsv) {
        this.granted = parse(grantedCsv);
        log.info("ToolPermissionPolicy granted permissions = {}", granted);
    }

    private Set<ToolPermission> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return EnumSet.copyOf(DEFAULT_GRANTS);
        }
        EnumSet<ToolPermission> set = EnumSet.noneOf(ToolPermission.class);
        Arrays.stream(csv.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .forEach(s -> {
                    try {
                        set.add(ToolPermission.valueOf(s));
                    } catch (IllegalArgumentException ex) {
                        log.warn("Unknown ToolPermission in config, ignoring: {}", s);
                    }
                });
        return set;
    }

    // --- tool-name entry point (gateway executor); names kept stable for reuse ---

    public Set<ToolPermission> requiredFor(String toolName) {
        return TOOL_CATALOG.getOrDefault(toolName, Set.of());
    }

    public boolean isAllowed(String toolName) {
        return granted.containsAll(requiredFor(toolName));
    }

    public Set<ToolPermission> missingFor(String toolName) {
        return missing(requiredFor(toolName));
    }

    // --- intent entry point (orchestrator / voice) ---

    /**
     * Required permissions for a voice/orchestrator intent, derived by keyword.
     * Conservative: anything that looks like spend/shell/file/media maps to the
     * matching guarded permission; PC/media/smart-home intents map accordingly.
     */
    public Set<ToolPermission> requiredForIntent(String intent) {
        if (intent == null || intent.isBlank()) {
            return Set.of();
        }
        String i = intent.trim().toLowerCase(Locale.ROOT);
        if (containsAny(i, "finance", "spend", "transfer", "purchase", "payment", "subscription")) {
            return EnumSet.of(ToolPermission.FINANCE_ACCESS);
        }
        if (containsAny(i, "shell", "exec", "run_command", "terminal_command", "bash")) {
            return EnumSet.of(ToolPermission.RUN_SHELL);
        }
        if (containsAny(i, "write_file", "delete_file", "fs.", "save_file", "remove_file")) {
            return EnumSet.of(ToolPermission.WRITE_FILES);
        }
        if (containsAny(i, "media", "dub", "subtitle", "ffmpeg", "transcode")) {
            return EnumSet.of(ToolPermission.MEDIA_ACCESS);
        }
        if (containsAny(i, "smart_home", "smarthome", "light", "thermostat", "door", "scene")) {
            return EnumSet.of(ToolPermission.SMART_HOME_ACCESS);
        }
        if (containsAny(i, "volume", "mute", "play", "pause", "track", "screenshot", "lock_screen",
                "window", "open_url", "open_app", "type_text", "focus", "hotkey")) {
            return EnumSet.of(ToolPermission.PC_CONTROL);
        }
        return Set.of();
    }

    public boolean isIntentAllowed(String intent) {
        return granted.containsAll(requiredForIntent(intent));
    }

    public Set<ToolPermission> missingForIntent(String intent) {
        return missing(requiredForIntent(intent));
    }

    public Set<ToolPermission> granted() {
        return EnumSet.copyOf(granted);
    }

    private Set<ToolPermission> missing(Set<ToolPermission> required) {
        EnumSet<ToolPermission> miss = EnumSet.noneOf(ToolPermission.class);
        for (ToolPermission p : required) {
            if (!granted.contains(p)) {
                miss.add(p);
            }
        }
        return miss;
    }

    private boolean containsAny(String value, String... needles) {
        for (String n : needles) {
            if (value.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
