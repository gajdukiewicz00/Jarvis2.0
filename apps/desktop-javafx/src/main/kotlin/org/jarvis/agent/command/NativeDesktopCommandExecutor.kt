package org.jarvis.agent.command

import org.jarvis.commands.CommandEnvelope
import org.jarvis.commands.CommandResult
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.Locale

/**
 * Phase 6 — replaces the {@link LoggingCommandExecutor} stub with real
 * desktop work. Recognises a small allowlist of safe intents:
 *
 * <ul>
 *   <li>{@code OPEN_APP}            — launch a process from an allowlist</li>
 *   <li>{@code FOCUS_WINDOW}        — bring a window to front by title</li>
 *   <li>{@code TYPE_TEXT}           — type text into the focused window</li>
 *   <li>{@code OPEN_URL}            — open a http/https/mailto URL</li>
 *   <li>{@code CREATE_LOCAL_NOTE}   — write a markdown note under ~/.jarvis/notes</li>
 *   <li>{@code SHOW_NOTIFICATION}   — desktop notification (notify-send)</li>
 *   <li>{@code GET_ACTIVE_WINDOW}   — return the focused window's title</li>
 * </ul>
 *
 * <p>Anything else returns a {@code REJECTED}-shaped FAILED result with a
 * clear reason: dangerous commands must travel the Phase-5 confirmation
 * flow, and the executor refuses to interpret unknown intents on its own.</p>
 *
 * <p>{@link AgentLiveFeed} subscription + {@link AuditForwarder} pickup are
 * driven by the existing {@link org.jarvis.agent.permission.PermissionAwareExecutor}
 * wrapper — this class only owns the action.</p>
 */
class NativeDesktopCommandExecutor(
    private val actions: DesktopActions = DefaultDesktopActions()
) : CommandExecutor {

    private val log = LoggerFactory.getLogger(NativeDesktopCommandExecutor::class.java)

    override fun execute(envelope: CommandEnvelope): CommandResult {
        val started = System.currentTimeMillis()
        val intent = envelope.intent?.uppercase(Locale.ROOT)?.trim()
        val payload = envelope.payload ?: emptyMap()
        log.info(
            "[{}] native executor running intent={} risk={}",
            envelope.commandId, intent, envelope.riskLevel
        )

        val outcome = try {
            dispatch(intent, payload)
        } catch (ex: Exception) {
            log.error("[{}] native executor threw: {}", envelope.commandId, ex.message, ex)
            DesktopActions.ActionResult.fail("executor exception: ${ex.javaClass.simpleName}: ${ex.message}")
        }

        val duration = System.currentTimeMillis() - started
        return if (outcome.ok) {
            CommandResult.success(
                envelope.commandId,
                envelope.correlationId,
                outcome.output + mapOf(
                    "executor" to "native-desktop",
                    "intent" to (intent ?: "")
                ),
                duration
            )
        } else {
            CommandResult.failed(
                envelope.commandId,
                envelope.correlationId,
                outcome.errorReason ?: "unknown failure",
                duration
            )
        }
    }

    private fun dispatch(intent: String?, payload: Map<String, Any?>): DesktopActions.ActionResult {
        return when (intent) {
            "OPEN_APP" -> actions.openApp(
                app = stringField(payload, "app") ?: stringField(payload, "command")
                    ?: return DesktopActions.ActionResult.fail("OPEN_APP: 'app' is required"),
                args = stringListField(payload, "args")
            )
            "FOCUS_WINDOW" -> actions.focusWindow(
                titleSubstring = stringField(payload, "title")
                    ?: return DesktopActions.ActionResult.fail("FOCUS_WINDOW: 'title' is required")
            )
            "TYPE_TEXT" -> actions.typeText(
                text = stringField(payload, "text")
                    ?: return DesktopActions.ActionResult.fail("TYPE_TEXT: 'text' is required"),
                perCharDelayMs = longField(payload, "delayMs") ?: 0L
            )
            "OPEN_URL" -> actions.openUrl(
                url = stringField(payload, "url")
                    ?: return DesktopActions.ActionResult.fail("OPEN_URL: 'url' is required")
            )
            "CREATE_LOCAL_NOTE" -> actions.createLocalNote(
                title = stringField(payload, "title")
                    ?: return DesktopActions.ActionResult.fail("CREATE_LOCAL_NOTE: 'title' is required"),
                body = stringField(payload, "body") ?: "",
                directory = stringField(payload, "directory")?.let(Path::of)
            )
            "SHOW_NOTIFICATION" -> actions.showNotification(
                summary = stringField(payload, "summary")
                    ?: return DesktopActions.ActionResult.fail("SHOW_NOTIFICATION: 'summary' is required"),
                body = stringField(payload, "body") ?: "",
                urgency = stringField(payload, "urgency") ?: "normal"
            )
            "GET_ACTIVE_WINDOW" -> actions.getActiveWindow()
            null, "" -> DesktopActions.ActionResult.fail("intent missing — desktop executor refuses to guess")
            else -> DesktopActions.ActionResult.fail(
                "intent '$intent' not implemented in safe executor (dangerous intents go through confirmation flow)"
            )
        }
    }

    private fun stringField(payload: Map<String, Any?>, key: String): String? {
        val raw = payload[key] ?: return null
        val s = raw.toString().trim()
        return s.ifEmpty { null }
    }

    private fun longField(payload: Map<String, Any?>, key: String): Long? {
        val raw = payload[key] ?: return null
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun stringListField(payload: Map<String, Any?>, key: String): List<String> {
        val raw = payload[key] ?: return emptyList()
        return when (raw) {
            is List<*> -> raw.mapNotNull { it?.toString() }
            is String -> raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }
}
