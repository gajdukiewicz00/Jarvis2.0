package org.jarvis.agent.confirmation

import org.jarvis.commands.ConfirmationDecision
import org.jarvis.commands.ConfirmationRequest
import org.slf4j.LoggerFactory
import java.util.Locale

/**
 * Phase 5 — pluggable confirmation policy on the agent side.
 *
 * <p>Pass 1 ships three concrete strategies:</p>
 * <ul>
 *   <li>{@link AutoDenyStrategy}     — paranoid baseline.</li>
 *   <li>{@link CliPromptStrategy}    — interactive Y/N prompt on stdin.</li>
 *   <li>{@link AutoApproveStrategy}  — TEST ONLY, gated by profile + flag.</li>
 * </ul>
 *
 * <p>Phase 6 (Native Desktop Agent Stabilization) replaces this with a
 * real JavaFX modal + voice-confirm path; it will keep the same
 * interface so the rest of the pipeline does not change.</p>
 *
 * <p>{@link #fromEnv} is the supported way to bind a strategy at startup.
 * It refuses to materialise {@link AutoApproveStrategy} unless the runtime
 * is explicitly running a non-prod profile AND the operator opted in via
 * {@code JARVIS_AGENT_ALLOW_AUTO_APPROVE=true}. Production deployments
 * crash on boot if those preconditions aren't met — no silent fall-back.</p>
 */
fun interface ConfirmationStrategy {
    /**
     * Decide whether to approve, deny, or block the request. Implementations
     * MUST return promptly; long-running interaction belongs in a separate
     * strategy that itself manages timeouts.
     */
    fun decide(request: ConfirmationRequest): Decision

    data class Decision(
        val decision: ConfirmationDecision,
        val decidedBy: String,
        val reason: String? = null
    )

    companion object {
        private val log = LoggerFactory.getLogger(ConfirmationStrategy::class.java)

        /** Profiles that may legitimately use the auto-approve strategy. */
        val NON_PROD_PROFILES: Set<String> = setOf("test", "dev", "ci", "smoke", "local")

        /**
         * Builds a strategy from the agent's runtime environment.
         *
         * <p>The default — even when the operator forgets to set the env —
         * is "require human decision" via the interactive
         * {@link CliPromptStrategy}, which denies on EOF and on blank
         * input. This is intentional: the safety model treats "no signal"
         * as "no permission".</p>
         */
        @JvmStatic
        @JvmOverloads
        fun fromEnv(
            envValue: String? = System.getenv("JARVIS_AGENT_CONFIRMATION_STRATEGY"),
            profile: String? = System.getenv("JARVIS_AGENT_PROFILE"),
            allowAutoApprove: Boolean =
                System.getenv("JARVIS_AGENT_ALLOW_AUTO_APPROVE")?.equals("true", ignoreCase = true) == true
        ): ConfirmationStrategy {
            val normalizedProfile = (profile ?: "prod").trim().lowercase(Locale.ROOT)
            val isNonProd = normalizedProfile in NON_PROD_PROFILES
            val requested = envValue?.trim()?.lowercase(Locale.ROOT)

            return when (requested) {
                "auto-deny", "deny", "block" -> AutoDenyStrategy()
                "cli", "stdin", "prompt", null, "" -> CliPromptStrategy()
                "auto-approve", "approve" -> {
                    check(isNonProd) {
                        "JARVIS_AGENT_CONFIRMATION_STRATEGY=$envValue is forbidden in profile='$normalizedProfile'. " +
                            "Auto-approve is only allowed when JARVIS_AGENT_PROFILE is one of " +
                            "$NON_PROD_PROFILES."
                    }
                    check(allowAutoApprove) {
                        "JARVIS_AGENT_CONFIRMATION_STRATEGY=$envValue requires JARVIS_AGENT_ALLOW_AUTO_APPROVE=true; " +
                            "this guards against accidental auto-approval in dev environments that mirror prod."
                    }
                    log.error(
                        "AUTO-APPROVE confirmation strategy is active (profile={}). Dangerous commands " +
                            "will be APPROVED without human input. This must NEVER be true in production.",
                        normalizedProfile
                    )
                    AutoApproveStrategy.forTests(
                        justification = "fromEnv profile=$normalizedProfile",
                        confirmTestUse = true
                    )
                }
                else -> throw IllegalStateException(
                    "Unknown JARVIS_AGENT_CONFIRMATION_STRATEGY='$envValue'. " +
                        "Allowed: auto-deny | cli | auto-approve (test profiles only)."
                )
            }
        }
    }
}
