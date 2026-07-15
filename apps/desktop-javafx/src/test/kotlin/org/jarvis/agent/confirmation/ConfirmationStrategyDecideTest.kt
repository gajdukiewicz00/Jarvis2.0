package org.jarvis.agent.confirmation

import org.jarvis.commands.ConfirmationDecision
import org.jarvis.commands.ConfirmationRequest
import org.jarvis.commands.RiskLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.Reader
import java.io.StringReader

/**
 * Covers the {@code decide()} behaviour of the concrete confirmation
 * strategies. [ConfirmationStrategyEnvTest] already covers strategy
 * *selection*; this suite covers what each strategy actually decides.
 * The [CliPromptStrategy] input source is injected, so no interactive
 * stdin is required.
 */
class ConfirmationStrategyDecideTest {

    private fun request(userId: String? = null): ConfirmationRequest = ConfirmationRequest.builder()
        .commandId("cmd-1")
        .correlationId("corr-1")
        .userId(userId)
        .intent("pc.shutdown")
        .riskLevel(RiskLevel.HIGH)
        .prompt("shut down the PC?")
        .build()

    private fun cli(input: String) = CliPromptStrategy(BufferedReader(StringReader(input)))

    // ---------- AutoDenyStrategy ----------

    @Test
    fun `AutoDenyStrategy always denies`() {
        val decision = AutoDenyStrategy().decide(request(userId = "owner-1"))
        assertEquals(ConfirmationDecision.DENIED, decision.decision)
        assertEquals("auto-deny", decision.decidedBy)
        assertTrue(decision.reason!!.contains("auto-deny"))
    }

    // ---------- AutoApproveStrategy ----------

    @Test
    fun `AutoApproveStrategy approves and echoes the justification`() {
        val strategy = AutoApproveStrategy.forTests(justification = "unit-test", confirmTestUse = true)
        val decision = strategy.decide(request())
        assertEquals(ConfirmationDecision.APPROVED, decision.decision)
        assertEquals("auto-approve", decision.decidedBy, "falls back to a synthetic operator when no userId")
        assertTrue(decision.reason!!.contains("unit-test"))
    }

    @Test
    fun `AutoApproveStrategy attributes the decision to the requesting user when present`() {
        val strategy = AutoApproveStrategy.forTests(justification = "j", confirmTestUse = true)
        val decision = strategy.decide(request(userId = "alice"))
        assertEquals("alice", decision.decidedBy)
    }

    // ---------- CliPromptStrategy ----------

    @Test
    fun `CliPromptStrategy approves on y`() {
        val decision = cli("y\n").decide(request())
        assertEquals(ConfirmationDecision.APPROVED, decision.decision)
        assertEquals("cli-operator", decision.decidedBy)
        assertTrue(decision.reason!!.contains("approved"))
    }

    @Test
    fun `CliPromptStrategy approves on yes approve and ok case-insensitively`() {
        assertEquals(ConfirmationDecision.APPROVED, cli("YES\n").decide(request()).decision)
        assertEquals(ConfirmationDecision.APPROVED, cli("Approve\n").decide(request()).decision)
        assertEquals(ConfirmationDecision.APPROVED, cli("ok\n").decide(request()).decision)
    }

    @Test
    fun `CliPromptStrategy denies on an explicit no`() {
        val decision = cli("n\n").decide(request())
        assertEquals(ConfirmationDecision.DENIED, decision.decision)
        assertTrue(decision.reason!!.contains("denied"))
    }

    @Test
    fun `CliPromptStrategy denies on a blank line`() {
        assertEquals(ConfirmationDecision.DENIED, cli("\n").decide(request()).decision)
    }

    @Test
    fun `CliPromptStrategy denies on EOF`() {
        assertEquals(ConfirmationDecision.DENIED, cli("").decide(request()).decision)
    }

    @Test
    fun `CliPromptStrategy denies when the reader throws`() {
        val throwingReader = BufferedReader(object : Reader() {
            override fun read(cbuf: CharArray, off: Int, len: Int): Int = throw IOException("stdin closed")
            override fun close() {}
        })
        val decision = CliPromptStrategy(throwingReader).decide(request())
        assertEquals(ConfirmationDecision.DENIED, decision.decision)
    }

    @Test
    fun `CliPromptStrategy attributes the decision to the requesting user when present`() {
        val decision = cli("y\n").decide(request(userId = "bob"))
        assertEquals("bob", decision.decidedBy)
    }
}
