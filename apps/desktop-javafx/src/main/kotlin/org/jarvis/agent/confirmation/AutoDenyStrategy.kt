package org.jarvis.agent.confirmation

import org.jarvis.commands.ConfirmationDecision
import org.jarvis.commands.ConfirmationRequest
import org.slf4j.LoggerFactory

/**
 * Phase 5 — denies every confirmation request. Used in tests and as a
 * paranoid baseline (e.g. when the human is asleep / away).
 */
class AutoDenyStrategy : ConfirmationStrategy {
    private val log = LoggerFactory.getLogger(AutoDenyStrategy::class.java)

    override fun decide(request: ConfirmationRequest): ConfirmationStrategy.Decision {
        log.info(
            "[{}] AUTO-DENY strategy: intent={} risk={}",
            request.commandId, request.intent, request.riskLevel
        )
        return ConfirmationStrategy.Decision(
            decision = ConfirmationDecision.DENIED,
            decidedBy = "auto-deny",
            reason = "auto-deny strategy active"
        )
    }
}
