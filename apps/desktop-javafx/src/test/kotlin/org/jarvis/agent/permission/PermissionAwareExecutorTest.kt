package org.jarvis.agent.permission

import org.jarvis.agent.command.CommandExecutor
import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.agent.killswitch.KillSwitchManager
import org.jarvis.commands.CommandEnvelope
import org.jarvis.commands.CommandResult
import org.jarvis.commands.CommandStatus
import org.jarvis.commands.agent.AgentEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PermissionAwareExecutorTest {

    private fun envelope(): CommandEnvelope = CommandEnvelope.builder()
        .commandId("cmd-1")
        .correlationId("corr-1")
        .intent("pc.window.focus")
        .build()

    @Test
    fun `executes the delegate and emits COMMAND_EXECUTED when the kill switch is clear`(@TempDir tempDir: Path) {
        val feed = AgentLiveFeed()
        val manager = KillSwitchManager(agentId = "agent-1", feed = feed, storeDir = tempDir)
        val gate = PermissionGate(manager, emptySet())
        val delegate = CommandExecutor { env ->
            CommandResult.success(env.commandId, env.correlationId, mapOf("ok" to true), 5)
        }

        val executor = PermissionAwareExecutor(delegate, gate, feed, "agent-1")
        val result = executor.execute(envelope())

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertEquals(AgentEvent.Type.COMMAND_EXECUTED, feed.recent().last().type)
    }

    @Test
    fun `rejects with a REJECTED result and warning event when the kill switch is engaged`(@TempDir tempDir: Path) {
        val feed = AgentLiveFeed()
        val manager = KillSwitchManager(agentId = "agent-1", feed = feed, storeDir = tempDir)
        manager.engage("operator", "unsafe")
        val gate = PermissionGate(manager, emptySet())
        var delegateCalled = false
        val delegate = CommandExecutor {
            delegateCalled = true
            CommandResult.success(it.commandId, it.correlationId, emptyMap(), 0)
        }

        val executor = PermissionAwareExecutor(delegate, gate, feed, "agent-1")
        val result = executor.execute(envelope())

        // PermissionAwareExecutor reports the kill-switch refusal via CommandResult.failed(),
        // which always carries CommandStatus.FAILED — the "REJECTED:" marker lives in errorReason.
        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(result.errorReason!!.contains("REJECTED"))
        assertTrue(!delegateCalled, "delegate must not run while the kill switch is engaged")
        assertEquals(AgentEvent.Type.KILL_SWITCH_ENGAGED, feed.recent().last().type)
    }

    @Test
    fun `converts an unexpected delegate exception into a FAILED result and ERROR event`(@TempDir tempDir: Path) {
        val feed = AgentLiveFeed()
        val manager = KillSwitchManager(agentId = "agent-1", feed = feed, storeDir = tempDir)
        val gate = PermissionGate(manager, emptySet())
        val delegate = CommandExecutor { throw IllegalStateException("boom") }

        val executor = PermissionAwareExecutor(delegate, gate, feed, "agent-1")
        val result = executor.execute(envelope())

        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(result.errorReason!!.contains("boom"))
        assertEquals(AgentEvent.Type.ERROR, feed.recent().last().type)
    }
}
