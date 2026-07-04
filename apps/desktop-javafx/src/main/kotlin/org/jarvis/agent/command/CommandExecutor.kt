package org.jarvis.agent.command

import org.jarvis.commands.CommandEnvelope
import org.jarvis.commands.CommandResult
import org.slf4j.LoggerFactory

/**
 * Phase 4 — pluggable executor that turns a {@link CommandEnvelope} into a
 * {@link CommandResult}.
 *
 * <p>Pass 1 ships a logging-only stub. Phase 6 (Native Desktop Agent
 * Stabilization) will route to real desktop actions (window focus, type
 * text, etc.); Phase 5 (confirmation) will wrap dangerous commands.</p>
 */
fun interface CommandExecutor {
    fun execute(envelope: CommandEnvelope): CommandResult
}

/**
 * Default Pass 1 executor: logs the intent, simulates a quick action, and
 * returns SUCCESS. Useful for proving the pipeline end-to-end before any
 * real desktop wiring lands.
 */
class LoggingCommandExecutor : CommandExecutor {
    private val log = LoggerFactory.getLogger(LoggingCommandExecutor::class.java)

    override fun execute(envelope: CommandEnvelope): CommandResult {
        val started = System.currentTimeMillis()
        log.info(
            "[{}] executing intent={} risk={} payload={}",
            envelope.commandId, envelope.intent, envelope.riskLevel, envelope.payload
        )
        // Simulate a short action — real wiring lands in Phase 6.
        val duration = System.currentTimeMillis() - started
        return CommandResult.success(
            envelope.commandId,
            envelope.correlationId,
            mapOf(
                "executor" to "logging-stub",
                "intent" to (envelope.intent ?: ""),
            ),
            duration
        )
    }
}
