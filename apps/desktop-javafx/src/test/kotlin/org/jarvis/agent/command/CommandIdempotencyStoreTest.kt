package org.jarvis.agent.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class CommandIdempotencyStoreTest {

    @Test
    fun `seenOrMark returns true the first time and false for a repeat`() {
        val store = CommandIdempotencyStore()

        assertTrue(store.seenOrMark("cmd-1"))
        assertFalse(store.seenOrMark("cmd-1"))
        assertEquals(1, store.duplicateCount())
        assertEquals(1, store.size())
    }

    @Test
    fun `distinct command ids are tracked independently`() {
        val store = CommandIdempotencyStore()

        assertTrue(store.seenOrMark("cmd-a"))
        assertTrue(store.seenOrMark("cmd-b"))
        assertEquals(0, store.duplicateCount())
        assertEquals(2, store.size())
    }

    @Test
    fun `entries older than the TTL are treated as new again`() {
        val store = CommandIdempotencyStore(ttl = Duration.ofMillis(20))

        assertTrue(store.seenOrMark("cmd-expiring"))
        Thread.sleep(200)
        // Caffeine's expireAfterWrite is opportunistic; poll size() to force cleanup.
        store.size()

        assertTrue(store.seenOrMark("cmd-expiring"), "entry should have expired and be treated as unseen")
    }
}
