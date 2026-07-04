package org.jarvis.agent.killswitch

import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.commands.agent.AgentEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class KillSwitchManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var feed: AgentLiveFeed
    private lateinit var manager: KillSwitchManager

    @BeforeEach
    fun setUp() {
        feed = AgentLiveFeed()
        manager = KillSwitchManager(agentId = "agent-test", feed = feed, storeDir = tempDir)
    }

    @AfterEach
    fun tearDown() {
        // tempDir cleanup is handled by JUnit
    }

    @Test
    fun `disengaged by default`() {
        assertFalse(manager.isEngaged())
        assertFalse(manager.current().isEngaged)
    }

    @Test
    fun `engage flips state and emits live-feed event`() {
        manager.engage("operator", "test reason")

        assertTrue(manager.isEngaged())
        val state = manager.current()
        assertEquals("operator", state.engagedBy)
        assertEquals("test reason", state.reason)
        assertNotNull(state.engagedAt)

        val event = feed.recent().last()
        assertEquals(AgentEvent.Type.KILL_SWITCH_ENGAGED, event.type)
        assertEquals("agent-test", event.agentId)
    }

    @Test
    fun `engage twice does not re-emit (idempotent)`() {
        manager.engage("operator", "first")
        val sizeAfterFirst = feed.size()
        manager.engage("operator2", "second")
        assertEquals(sizeAfterFirst, feed.size(),
            "engaging while already engaged should NOT emit a new event")
        assertEquals("operator", manager.current().engagedBy)
    }

    @Test
    fun `disengage clears state and emits event`() {
        manager.engage("operator", "x")
        manager.disengage("operator")

        assertFalse(manager.isEngaged())
        val event = feed.recent().last()
        assertEquals(AgentEvent.Type.KILL_SWITCH_DISENGAGED, event.type)
    }

    @Test
    fun `ensureClear throws when engaged`() {
        manager.engage("operator", "blocked")
        assertThrows(KillSwitchEngagedException::class.java) { manager.ensureClear() }
    }

    @Test
    fun `engaged state survives reload from disk`() {
        manager.engage("operator", "persisted")
        val reborn = KillSwitchManager(agentId = "agent-test", feed = AgentLiveFeed(), storeDir = tempDir)
        reborn.load()
        assertTrue(reborn.isEngaged())
        assertEquals("operator", reborn.current().engagedBy)
        assertEquals("persisted", reborn.current().reason)
    }

    @Test
    fun `disengage on disengaged is no-op`() {
        manager.disengage("anyone")
        assertFalse(manager.isEngaged())
        assertEquals(0, feed.size())
    }
}
