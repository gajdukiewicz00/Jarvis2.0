package org.jarvis.orchestrator.command.risk;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.commands.DangerousAction;
import org.jarvis.commands.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 5 — central authority for the risk level of every named intent.
 *
 * <p>Maps {@link org.jarvis.commands.CommandEnvelope#getIntent()} to a
 * {@link RiskClassification}. Unknown intents default to
 * {@link RiskLevel#MEDIUM} as a safety policy: anything we have not
 * explicitly catalogued must require human confirmation.</p>
 *
 * <p>The catalog mirrors SPEC-1's "dangerous commands" list:</p>
 * <ul>
 *   <li>delete files                — {@link DangerousAction#DELETE_FILES}</li>
 *   <li>send messages               — {@link DangerousAction#SEND_MESSAGES}</li>
 *   <li>run shell command           — {@link DangerousAction#RUN_SHELL}</li>
 *   <li>spend money                 — {@link DangerousAction#SPEND_MONEY}</li>
 *   <li>open doors                  — {@link DangerousAction#OPEN_DOORS}</li>
 *   <li>shutdown / reboot / sleep   — {@link DangerousAction#SHUTDOWN}</li>
 *   <li>change security settings    — {@link DangerousAction#CHANGE_SECURITY}</li>
 *   <li>bulk memory modification    — {@link DangerousAction#BULK_MEMORY_MODIFY}</li>
 * </ul>
 *
 * <p>Phase 5 ships a static map; later phases can promote this to a
 * configurable / hot-reloadable catalog without changing the API.</p>
 */
@Slf4j
@Component
public class IntentRiskCatalog {

    /**
     * Static catalog. Keep it conservative — when in doubt, classify
     * higher; the user can always relax via configuration in a future
     * phase, but a misclassified destructive action is a real incident.
     */
    private static final Map<String, RiskClassification> CATALOG = Map.<String, RiskClassification>ofEntries(
            // --- SAFE / read-only ---
            entry("system.status",                  RiskLevel.SAFE,    null),
            entry("system.health",                  RiskLevel.SAFE,    null),
            entry("memory.search",                  RiskLevel.SAFE,    null),
            entry("memory.read",                    RiskLevel.SAFE,    null),
            entry("calendar.read",                  RiskLevel.SAFE,    null),
            entry("finance.read",                   RiskLevel.SAFE,    null),
            entry("life.read",                      RiskLevel.SAFE,    null),
            entry("planner.read",                   RiskLevel.SAFE,    null),
            // Voice read-only queries (nlp snake_case). Without these the catalog
            // default (MEDIUM) forces a confirmation the voice path can't approve,
            // so "который час" times out instead of answering.
            entry("get_time",                       RiskLevel.SAFE,    null),
            entry("get_date",                       RiskLevel.SAFE,    null),

            // --- LOW / reversible side-effects ---
            entry("pc.window.focus",                RiskLevel.LOW,     null),
            entry("pc.window.minimize",             RiskLevel.LOW,     null),
            entry("pc.window.maximize",             RiskLevel.LOW,     null),
            entry("pc.workspace.switch",            RiskLevel.LOW,     null),
            entry("pc.app.open",                    RiskLevel.LOW,     null),
            entry("pc.text.type",                   RiskLevel.LOW,     null),
            entry("pc.volume.set",                  RiskLevel.LOW,     null),
            entry("home.light.on",                  RiskLevel.LOW,     null),
            entry("home.light.off",                 RiskLevel.LOW,     null),
            entry("home.thermostat.set",            RiskLevel.LOW,     null),
            entry("planner.create-task",            RiskLevel.LOW,     null),
            entry("planner.update-task",            RiskLevel.LOW,     null),
            entry("memory.write",                   RiskLevel.LOW,     null),

            // --- P2 / Jarvis-loop desktop allowlist (executor-canonical names) ---
            entry("open_url",                       RiskLevel.LOW,     null),
            entry("open_app",                       RiskLevel.LOW,     null),
            entry("create_local_note",              RiskLevel.LOW,     null),
            entry("focus_window",                   RiskLevel.LOW,     null),
            entry("type_text",                      RiskLevel.LOW,     null),
            entry("show_notification",              RiskLevel.SAFE,    null),
            entry("get_active_window",              RiskLevel.SAFE,    null),

            // --- Voice media/volume control (nlp snake_case names) ---
            // Reversible, low-stakes. Without these the catalog default (MEDIUM)
            // forces a confirmation the voice path can't approve, so "сделай тише"
            // and friends time out and never execute (symptom: "doesn't control PC").
            entry("volume_up",                      RiskLevel.LOW,     null),
            entry("volume_down",                    RiskLevel.LOW,     null),
            entry("volume_set",                     RiskLevel.LOW,     null),
            entry("mute",                           RiskLevel.LOW,     null),
            entry("unmute",                         RiskLevel.LOW,     null),
            entry("play",                           RiskLevel.LOW,     null),
            entry("pause",                          RiskLevel.LOW,     null),
            entry("next_track",                     RiskLevel.LOW,     null),
            entry("previous_track",                 RiskLevel.LOW,     null),

            // --- MEDIUM ---
            entry("pc.app.close",                   RiskLevel.MEDIUM,  null),
            entry("planner.delete-task",            RiskLevel.MEDIUM,  null),
            entry("calendar.create-event",          RiskLevel.MEDIUM,  null),
            entry("calendar.delete-event",          RiskLevel.MEDIUM,  null),
            entry("memory.delete-entry",            RiskLevel.MEDIUM,  null),

            // --- HIGH (dangerous-action mapped) ---
            entry("fs.delete-file",                 RiskLevel.HIGH,    DangerousAction.DELETE_FILES),
            entry("fs.delete-directory",            RiskLevel.HIGH,    DangerousAction.DELETE_FILES),
            entry("fs.move-to-trash",               RiskLevel.HIGH,    DangerousAction.DELETE_FILES),

            entry("chat.send-message",              RiskLevel.HIGH,    DangerousAction.SEND_MESSAGES),
            entry("email.send",                     RiskLevel.HIGH,    DangerousAction.SEND_MESSAGES),
            entry("sms.send",                       RiskLevel.HIGH,    DangerousAction.SEND_MESSAGES),

            entry("memory.delete-bulk",             RiskLevel.HIGH,    DangerousAction.BULK_MEMORY_MODIFY),
            entry("memory.purge-vault",             RiskLevel.HIGH,    DangerousAction.BULK_MEMORY_MODIFY),

            entry("security.rotate-token",          RiskLevel.HIGH,    DangerousAction.CHANGE_SECURITY),
            entry("security.update-password",       RiskLevel.HIGH,    DangerousAction.CHANGE_SECURITY),
            entry("security.disable-mfa",           RiskLevel.HIGH,    DangerousAction.CHANGE_SECURITY),

            // --- CRITICAL ---
            entry("shell.exec",                     RiskLevel.CRITICAL, DangerousAction.RUN_SHELL),
            entry("shell.script",                   RiskLevel.CRITICAL, DangerousAction.RUN_SHELL),

            entry("finance.transfer",               RiskLevel.CRITICAL, DangerousAction.SPEND_MONEY),
            entry("finance.purchase",               RiskLevel.CRITICAL, DangerousAction.SPEND_MONEY),
            entry("finance.subscription.create",    RiskLevel.CRITICAL, DangerousAction.SPEND_MONEY),

            entry("home.door.unlock",               RiskLevel.CRITICAL, DangerousAction.OPEN_DOORS),
            entry("home.gate.open",                 RiskLevel.CRITICAL, DangerousAction.OPEN_DOORS),

            entry("pc.shutdown",                    RiskLevel.CRITICAL, DangerousAction.SHUTDOWN),
            entry("pc.reboot",                      RiskLevel.CRITICAL, DangerousAction.SHUTDOWN),
            entry("pc.sleep",                       RiskLevel.CRITICAL, DangerousAction.SHUTDOWN),
            entry("pc.logout",                      RiskLevel.CRITICAL, DangerousAction.SHUTDOWN),

            entry("agent.kill-switch",              RiskLevel.CRITICAL, DangerousAction.CHANGE_SECURITY)
    );

    private static Map.Entry<String, RiskClassification> entry(String name, RiskLevel level,
                                                                DangerousAction action) {
        return Map.entry(name, new RiskClassification(level, action));
    }

    /**
     * Resolve the risk classification for an intent name. Unknown intents
     * are treated as MEDIUM with no specific dangerous-action mapping —
     * SPEC-1 requires confirmation by default.
     */
    public RiskClassification classify(String intent) {
        if (intent == null || intent.isBlank()) {
            log.warn("classifying blank intent — defaulting to MEDIUM");
            return new RiskClassification(RiskLevel.MEDIUM, null);
        }
        RiskClassification known = lookup(intent);
        if (known != null) {
            return known;
        }
        log.info("intent '{}' not in catalog — defaulting to MEDIUM (confirmation required)",
                intent);
        return new RiskClassification(RiskLevel.MEDIUM, null);
    }

    public Optional<RiskClassification> known(String intent) {
        return Optional.ofNullable(lookup(intent));
    }

    private RiskClassification lookup(String intent) {
        if (intent == null) return null;
        RiskClassification direct = CATALOG.get(intent);
        if (direct != null) return direct;
        return CATALOG.get(intent.toLowerCase(Locale.ROOT));
    }

    public int size() {
        return CATALOG.size();
    }
}
