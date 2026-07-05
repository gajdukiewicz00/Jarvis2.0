package org.jarvis.agent.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * [AgentIdentityStore] takes an explicit [storeDir], so — unlike
 * [org.jarvis.launcher.JarvisPaths] — every test here is fully isolated
 * from the real `~/.jarvis` tree.
 */
class AgentIdentityStoreTest {

    @Test
    fun `loadOrCreate generates and persists a new identity on first run`(@TempDir tempDir: Path) {
        val store = AgentIdentityStore(storeDir = tempDir, agentVersion = "test-1")
        val identity = store.loadOrCreate()

        assertNotNull(identity.agentId)
        assertTrue(identity.agentId!!.startsWith("agent-"))
        assertNotNull(identity.hostId)
        assertTrue(identity.hostId!!.startsWith("host-"))
        assertEquals("test-1", identity.agentVersion)
        assertTrue(java.nio.file.Files.exists(tempDir.resolve("identity.json")))
    }

    @Test
    fun `loadOrCreate reuses the persisted agentId and hostId across restarts`(@TempDir tempDir: Path) {
        val first = AgentIdentityStore(storeDir = tempDir, agentVersion = "v1").loadOrCreate()
        val second = AgentIdentityStore(storeDir = tempDir, agentVersion = "v2").loadOrCreate()

        assertEquals(first.agentId, second.agentId)
        assertEquals(first.hostId, second.hostId)
        assertEquals("v2", second.agentVersion, "agentVersion should refresh to the running build")
    }

    @Test
    fun `loadOrCreate regenerates identity when the file is corrupt`(@TempDir tempDir: Path) {
        java.nio.file.Files.createDirectories(tempDir)
        java.nio.file.Files.writeString(tempDir.resolve("identity.json"), "{ not valid json")

        val identity = AgentIdentityStore(storeDir = tempDir).loadOrCreate()

        assertNotNull(identity.agentId)
        assertTrue(identity.agentId!!.startsWith("agent-"))
    }
}
