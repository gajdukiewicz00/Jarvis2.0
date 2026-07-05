package org.jarvis.agent.permission

import org.jarvis.agent.confirmation.ConfirmationStrategy
import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.agent.killswitch.KillSwitchManager
import org.jarvis.commands.ConfirmationDecision
import org.jarvis.commands.ConfirmationRequest
import org.jarvis.commands.agent.AgentEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class KillSwitchAwareConfirmationStrategyTest {

    private fun request(): ConfirmationRequest = ConfirmationRequest.builder()
        .commandId("cmd-1")
        .correlationId("corr-1")
        .intent("pc.shutdown")
        .build()

    @Test
    fun `delegates to the wrapped strategy when the kill switch is clear`(@TempDir tempDir: Path) {
        val feed = AgentLiveFeed()
        val manager = KillSwitchManager(agentId = "agent-1", feed = feed, storeDir = tempDir)
        val delegate = ConfirmationStrategy { ConfirmationStrategy.Decision(ConfirmationDecision.APPROVED, "test-operator") }

        val strategy = KillSwitchAwareConfirmationStrategy(delegate, manager, feed, "agent-1")
        val decision = strategy.decide(request())

        assertEquals(ConfirmationDecision.APPROVED, decision.decision)
        assertEquals("test-operator", decision.decidedBy)
    }

    @Test
    fun `auto-denies and emits a live-feed warning when the kill switch is engaged`(@TempDir tempDir: Path) {
        val feed = AgentLiveFeed()
        val manager = KillSwitchManager(agentId = "agent-1", feed = feed, storeDir = tempDir)
        manager.engage("operator", "unsafe state")

        var delegateCalled = false
        val delegate = ConfirmationStrategy {
            delegateCalled = true
            ConfirmationStrategy.Decision(ConfirmationDecision.APPROVED, "should-not-run")
        }

        val strategy = KillSwitchAwareConfirmationStrategy(delegate, manager, feed, "agent-1")
        val decision = strategy.decide(request())

        assertEquals(ConfirmationDecision.DENIED, decision.decision)
        assertEquals("kill-switch", decision.decidedBy)
        assertTrue(decision.reason!!.contains("kill switch engaged by operator"))
        assertTrue(!delegateCalled, "the delegate must not be consulted while the kill switch is engaged")

        val lastEvent = feed.recent().last()
        assertEquals(AgentEvent.Type.KILL_SWITCH_ENGAGED, lastEvent.type)
    }

    @Test
    fun `emits a CONFIRMATION_REQUESTED event before delegating when clear`(@TempDir tempDir: Path) {
        val feed = AgentLiveFeed()
        val manager = KillSwitchManager(agentId = "agent-1", feed = feed, storeDir = tempDir)
        val delegate = ConfirmationStrategy { ConfirmationStrategy.Decision(ConfirmationDecision.DENIED, "cli") }

        val strategy = KillSwitchAwareConfirmationStrategy(delegate, manager, feed, "agent-1")
        strategy.decide(request())

        assertEquals(AgentEvent.Type.CONFIRMATION_REQUESTED, feed.recent().last().type)
    }
}
