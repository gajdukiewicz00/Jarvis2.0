package org.jarvis.android.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PendingItem] is a plain Kotlin data class (Room's `@Entity`/`@PrimaryKey` are compile-time
 * metadata only) — it needs no database, Context, or Android runtime to construct or compare.
 */
class PendingItemTest {

    @Test
    fun defaultsAreAppliedWhenOptionalFieldsOmitted() {
        val item = PendingItem(
            id = "id-1",
            kind = "FINANCE_ENTRY",
            payloadJson = "{}",
            createdAtEpochMs = 1000L
        )

        assertEquals(0, item.attemptCount)
        assertNull(item.lastAttemptEpochMs)
        assertNull(item.lastError)
        assertNull(item.syncedAtEpochMs)
    }

    @Test
    fun copyProducesIndependentUpdatedInstance() {
        val original = PendingItem(
            id = "id-1", kind = "FINANCE_ENTRY", payloadJson = "{}", createdAtEpochMs = 1000L
        )

        val failed = original.copy(attemptCount = 1, lastError = "http=500", lastAttemptEpochMs = 2000L)

        assertEquals(0, original.attemptCount)
        assertNull(original.lastError)
        assertEquals(1, failed.attemptCount)
        assertEquals("http=500", failed.lastError)
        assertEquals("id-1", failed.id)
        assertNotEquals(original, failed)
    }

    @Test
    fun equalsAndHashCodeAreValueBased() {
        val a = PendingItem(id = "same", kind = "K", payloadJson = "{}", createdAtEpochMs = 5L)
        val b = PendingItem(id = "same", kind = "K", payloadJson = "{}", createdAtEpochMs = 5L)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun toStringIncludesIdAndKind() {
        val item = PendingItem(id = "id-9", kind = "HEALTH_ENTRY", payloadJson = "{}", createdAtEpochMs = 1L)

        val text = item.toString()

        assertTrue(text.contains("id-9"))
        assertTrue(text.contains("HEALTH_ENTRY"))
    }
}
