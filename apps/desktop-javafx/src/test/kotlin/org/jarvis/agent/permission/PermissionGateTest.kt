package org.jarvis.agent.permission

import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.agent.killswitch.KillSwitchEngagedException
import org.jarvis.agent.killswitch.KillSwitchManager
import org.jarvis.commands.agent.AgentCapability
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PermissionGateTest {

    private fun gateWith(
        tempDir: Path,
        capabilities: Set<AgentCapability> = setOf(AgentCapability.FILE_SYSTEM)
    ): Pair<PermissionGate, KillSwitchManager> {
        val manager = KillSwitchManager(agentId = "agent-test", feed = AgentLiveFeed(), storeDir = tempDir)
        return PermissionGate(manager, capabilities) to manager
    }

    @Test
    fun `has reports whether a capability is present`(@TempDir tempDir: Path) {
        val (gate, _) = gateWith(tempDir, setOf(AgentCapability.FILE_SYSTEM))
        assertTrue(gate.has(AgentCapability.FILE_SYSTEM))
        assertFalse(gate.has(AgentCapability.WEBCAM))
    }

    @Test
    fun `require passes when capability is present and kill switch is clear`(@TempDir tempDir: Path) {
        val (gate, _) = gateWith(tempDir, setOf(AgentCapability.FILE_SYSTEM))
        gate.require(AgentCapability.FILE_SYSTEM) // must not throw
    }

    @Test
    fun `require throws CapabilityMissingException when capability is absent`(@TempDir tempDir: Path) {
        val (gate, _) = gateWith(tempDir, emptySet())
        val ex = assertThrows(PermissionGate.CapabilityMissingException::class.java) {
            gate.require(AgentCapability.WEBCAM)
        }
        assertTrue(ex.message!!.contains("WEBCAM"))
    }

    @Test
    fun `require throws KillSwitchEngagedException before checking capability`(@TempDir tempDir: Path) {
        val (gate, manager) = gateWith(tempDir, setOf(AgentCapability.FILE_SYSTEM))
        manager.engage("operator", "safety stop")
        assertThrows(KillSwitchEngagedException::class.java) {
            gate.require(AgentCapability.FILE_SYSTEM)
        }
    }

    @Test
    fun `requireAny passes when at least one requested capability is present`(@TempDir tempDir: Path) {
        val (gate, _) = gateWith(tempDir, setOf(AgentCapability.WEBCAM))
        gate.requireAny(AgentCapability.FILE_SYSTEM, AgentCapability.WEBCAM) // must not throw
    }

    @Test
    fun `requireAny throws with the first requested capability when none are present`(@TempDir tempDir: Path) {
        val (gate, _) = gateWith(tempDir, emptySet())
        val ex = assertThrows(PermissionGate.CapabilityMissingException::class.java) {
            gate.requireAny(AgentCapability.FILE_SYSTEM, AgentCapability.WEBCAM)
        }
        assertTrue(ex.message!!.contains("FILE_SYSTEM"))
    }

    @Test
    fun `ensureClearOnly throws only when the kill switch is engaged`(@TempDir tempDir: Path) {
        val (gate, manager) = gateWith(tempDir)
        gate.ensureClearOnly() // must not throw while clear

        manager.engage("operator", "stop")
        assertThrows(KillSwitchEngagedException::class.java) { gate.ensureClearOnly() }
    }
}
