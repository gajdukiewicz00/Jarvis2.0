package org.jarvis.android.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PendingItemDao] is a Room `@Dao` interface — Room's generated implementation needs a real
 * SQLite/Android runtime we don't have here (no Robolectric). But `recent(limit: Int = 50)`'s
 * default-parameter dispatch is plain Kotlin-compiler-generated bridge code (an interface
 * `DefaultImpls.recent$default`) that every implementation goes through, including a
 * hand-written fake. That lets the default-value wiring be verified on pure JVM.
 */
class PendingItemDaoDefaultsTest {

    private class FakeDao : PendingItemDao {
        var lastRequestedLimit: Int? = null

        override suspend fun upsert(item: PendingItem) {}
        override suspend fun pending(): List<PendingItem> = emptyList()
        override fun recent(limit: Int): Flow<List<PendingItem>> {
            lastRequestedLimit = limit
            return flowOf(emptyList())
        }
        override suspend fun markSynced(id: String, ts: Long) {}
        override suspend fun markFailed(id: String, ts: Long, err: String) {}
        override suspend fun pruneSyncedBefore(ts: Long) {}
    }

    @Test
    fun recent_usesFiftyAsDefaultLimitWhenNotSpecified() {
        val dao = FakeDao()

        dao.recent()

        assertEquals(50, dao.lastRequestedLimit)
    }

    @Test
    fun recent_honorsExplicitLimit() {
        val dao = FakeDao()

        dao.recent(10)

        assertEquals(10, dao.lastRequestedLimit)
    }
}
