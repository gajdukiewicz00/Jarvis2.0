package org.jarvis.agent.permission

import org.jarvis.agent.command.CommandExecutor
import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.agent.killswitch.KillSwitchEngagedException
import org.jarvis.commands.CommandEnvelope
import org.jarvis.commands.CommandResult
import org.jarvis.commands.agent.AgentEvent
import org.slf4j.LoggerFactory

/**
 * Phase 6 — wraps any {@link CommandExecutor} with a kill-switch and
 * (Pass 2) capability check so privileged work cannot bypass the gate.
 *
 * <p>Throws-then-catches pattern: the underlying gate raises
 * {@link KillSwitchEngagedException}; this wrapper converts it to a
 * REJECTED {@link CommandResult} and emits an {@code ERROR} live-feed
 * entry, so the orchestrator's caller future completes promptly instead
 * of timing out.</p>
 */
class PermissionAwareExecutor(
    private val delegate: CommandExecutor,
    private val gate: PermissionGate,
    private val feed: AgentLiveFeed,
    private val agentId: String
) : CommandExecutor {

    private val log = LoggerFactory.getLogger(PermissionAwareExecutor::class.java)

    override fun execute(envelope: CommandEnvelope): CommandResult {
        return try {
            gate.ensureClearOnly()
            val result = delegate.execute(envelope)
            feed.emit(AgentEvent.info(
                agentId,
                AgentEvent.Type.COMMAND_EXECUTED,
                "executed intent=${envelope.intent} cmd=${envelope.commandId}",
                mapOf(
                    "commandId" to envelope.commandId,
                    "intent" to (envelope.intent ?: ""),
                    "status" to result.status.toString(),
                    "durationMillis" to result.durationMillis
                )
            ))
            result
        } catch (ex: KillSwitchEngagedException) {
            log.warn("[{}] kill switch ENGAGED — refusing to execute intent={}",
                envelope.commandId, envelope.intent)
            feed.emit(AgentEvent.warn(
                agentId,
                AgentEvent.Type.KILL_SWITCH_ENGAGED,
                "refused command: ${ex.message}",
                mapOf("commandId" to envelope.commandId, "intent" to (envelope.intent ?: ""))
            ))
            CommandResult.failed(
                envelope.commandId,
                envelope.correlationId,
                "REJECTED: ${ex.message}",
                0
            )
        } catch (ex: Exception) {
            log.error("[{}] executor error: {}", envelope.commandId, ex.message, ex)
            feed.emit(AgentEvent.error(
                agentId,
                AgentEvent.Type.ERROR,
                "executor exception: ${ex.javaClass.simpleName}: ${ex.message}",
                mapOf("commandId" to envelope.commandId)
            ))
            CommandResult.failed(
                envelope.commandId,
                envelope.correlationId,
                "executor exception: ${ex.javaClass.simpleName}: ${ex.message}",
                0
            )
        }
    }
}
