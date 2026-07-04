package org.jarvis.agent.feed

import org.jarvis.commands.agent.AgentEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class AgentLiveFeedTest {

    @Test
    fun `emit appends and recent returns last N`() {
        val feed = AgentLiveFeed(capacity = 100)
        for (i in 0 until 10) {
            feed.emit(AgentEvent.info("agent-1", AgentEvent.Type.COMMAND_QUEUED,
                "msg-$i", emptyMap()))
        }
        val recent = feed.recent(limit = 5)
        assertEquals(5, recent.size)
        assertEquals("msg-5", recent.first().message)
        assertEquals("msg-9", recent.last().message)
    }

    @Test
    fun `capacity drops oldest events`() {
        val feed = AgentLiveFeed(capacity = 3)
        for (i in 0 until 10) {
            feed.emit(AgentEvent.info("a", AgentEvent.Type.COMMAND_EXECUTED, "m-$i", emptyMap()))
        }
        assertEquals(3, feed.size())
        val all = feed.recent(limit = 10)
        assertEquals("m-7", all.first().message)
        assertEquals("m-9", all.last().message)
        assertEquals(7, feed.droppedCount())
    }

    @Test
    fun `subscribers receive every event`() {
        val feed = AgentLiveFeed()
        val seen = AtomicInteger(0)
        feed.subscribe { seen.incrementAndGet() }
        for (i in 0 until 25) {
            feed.emit(AgentEvent.info("a", AgentEvent.Type.MEMORY_WRITTEN, "m", emptyMap()))
        }
        assertEquals(25, seen.get())
    }

    @Test
    fun `failing subscriber does not break the feed`() {
        val feed = AgentLiveFeed()
        val good = AtomicInteger(0)
        feed.subscribe { throw RuntimeException("boom") }
        feed.subscribe { good.incrementAndGet() }
        feed.emit(AgentEvent.info("a", AgentEvent.Type.ERROR, "x", emptyMap()))
        assertTrue(good.get() == 1, "good subscriber must still see the event")
        assertEquals(1, feed.size())
    }
}
