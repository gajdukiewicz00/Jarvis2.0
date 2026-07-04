package org.jarvis.agent.permission

import org.jarvis.agent.confirmation.ConfirmationStrategy
import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.agent.killswitch.KillSwitchManager
import org.jarvis.commands.ConfirmationDecision
import org.jarvis.commands.ConfirmationRequest
import org.jarvis.commands.agent.AgentEvent

/**
 * Phase 6 — wraps any {@link ConfirmationStrategy} so it auto-denies while
 * the kill switch is engaged. The orchestrator sees a normal DENIED
 * decision (with "kill switch engaged" reason) and the live feed records
 * the block.
 */
class KillSwitchAwareConfirmationStrategy(
    private val delegate: ConfirmationStrategy,
    private val killSwitch: KillSwitchManager,
    private val feed: AgentLiveFeed,
    private val agentId: String
) : ConfirmationStrategy {

    override fun decide(request: ConfirmationRequest): ConfirmationStrategy.Decision {
        if (killSwitch.isEngaged()) {
            val state = killSwitch.current()
            val reason = "kill switch engaged by ${state.engagedBy ?: "unknown"} at ${state.engagedAt}"
            feed.emit(AgentEvent.warn(
                agentId,
                AgentEvent.Type.KILL_SWITCH_ENGAGED,
                "auto-denied confirmation for ${request.commandId}: $reason",
                mapOf("commandId" to request.commandId, "intent" to (request.intent ?: ""))
            ))
            return ConfirmationStrategy.Decision(
                decision = ConfirmationDecision.DENIED,
                decidedBy = "kill-switch",
                reason = reason
            )
        }
        feed.emit(AgentEvent.info(
            agentId,
            AgentEvent.Type.CONFIRMATION_REQUESTED,
            "confirmation prompt: ${request.intent}",
            mapOf("commandId" to request.commandId, "intent" to (request.intent ?: ""))
        ))
        return delegate.decide(request)
    }
}
