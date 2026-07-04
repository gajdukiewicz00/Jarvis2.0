package org.jarvis.agent.confirmation

import org.jarvis.commands.ConfirmationDecision
import org.jarvis.commands.ConfirmationRequest
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Phase 5 — interactive stdin prompt for confirmation requests.
 * Useful when running {@link org.jarvis.agent.AgentMain} from a terminal.
 *
 * <p>Reads a single line:</p>
 * <ul>
 *   <li>{@code y / yes / approve} → {@code APPROVED}</li>
 *   <li>{@code n / no / deny}     → {@code DENIED}</li>
 *   <li>EOF or blank line         → {@code DENIED} (safer default)</li>
 * </ul>
 */
class CliPromptStrategy(
    private val reader: BufferedReader = BufferedReader(InputStreamReader(System.`in`))
) : ConfirmationStrategy {
    private val log = LoggerFactory.getLogger(CliPromptStrategy::class.java)

    override fun decide(request: ConfirmationRequest): ConfirmationStrategy.Decision {
        synchronized(reader) {
            println()
            println("================ JARVIS CONFIRMATION ================")
            println("commandId : ${request.commandId}")
            println("intent    : ${request.intent}")
            println("risk      : ${request.riskLevel}")
            println("action    : ${request.dangerousAction ?: "-"}")
            println("payload   : ${request.payload}")
            println("prompt    : ${request.prompt}")
            print("Approve? [y/N] > ")
            System.out.flush()

            val line = try {
                reader.readLine()
            } catch (ex: Exception) {
                log.warn("[{}] stdin read failed: {} — denying", request.commandId, ex.message)
                null
            }
            val approved = line?.trim()?.lowercase() in setOf("y", "yes", "approve", "ok")
            val decision =
                if (approved) ConfirmationDecision.APPROVED else ConfirmationDecision.DENIED
            return ConfirmationStrategy.Decision(
                decision = decision,
                decidedBy = request.userId ?: "cli-operator",
                reason = if (approved) "approved via CLI" else "denied via CLI (or EOF)"
            )
        }
    }
}
