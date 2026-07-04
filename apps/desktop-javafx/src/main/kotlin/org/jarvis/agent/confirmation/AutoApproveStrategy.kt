package org.jarvis.agent.confirmation

import org.jarvis.commands.ConfirmationDecision
import org.jarvis.commands.ConfirmationRequest
import org.slf4j.LoggerFactory

/**
 * Phase 5 — auto-approves every confirmation request.
 *
 * <p>NEVER usable in production: SPEC-1 forbids approving destructive
 * actions without an owner-side decision. Construction is gated behind an
 * explicit {@code confirmTestUse=true} flag so a misconfigured build can't
 * silently fall back to auto-approve. {@link ConfirmationStrategy#fromEnv}
 * is the only place that should normally call this constructor, and it
 * itself fails fast in non-test profiles.</p>
 */
class AutoApproveStrategy private constructor(private val justification: String) : ConfirmationStrategy {
    private val log = LoggerFactory.getLogger(AutoApproveStrategy::class.java)

    init {
        log.warn(
            "AutoApproveStrategy active — every dangerous command will be APPROVED automatically. " +
                "Reason: {}. THIS MUST NEVER RUN IN PRODUCTION.",
            justification
        )
    }

    override fun decide(request: ConfirmationRequest): ConfirmationStrategy.Decision {
        log.warn(
            "[{}] AUTO-APPROVE: intent={} risk={} ({})",
            request.commandId, request.intent, request.riskLevel, justification
        )
        return ConfirmationStrategy.Decision(
            decision = ConfirmationDecision.APPROVED,
            decidedBy = request.userId ?: "auto-approve",
            reason = "auto-approve: $justification"
        )
    }

    companion object {
        /**
         * Test-only factory. Throws unless the caller explicitly asserts
         * the strategy is for tests, with a justification for the audit
         * trail.
         */
        @JvmStatic
        fun forTests(justification: String, confirmTestUse: Boolean): AutoApproveStrategy {
            check(confirmTestUse) {
                "AutoApproveStrategy requires confirmTestUse=true; production builds must use a human-decided strategy"
            }
            require(justification.isNotBlank()) {
                "AutoApproveStrategy requires a non-blank justification for auditability"
            }
            return AutoApproveStrategy(justification)
        }
    }
}
