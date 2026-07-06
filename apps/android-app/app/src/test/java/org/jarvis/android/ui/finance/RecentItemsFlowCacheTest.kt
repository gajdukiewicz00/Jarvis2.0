package org.jarvis.android.ui.finance

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.jarvis.android.data.local.PendingItem
import org.jarvis.android.data.local.PendingItemDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for [RecentItemsFlowCache] — extracted from the buggy
 * `dao.recent(20)` call in [ManualFinanceScreen]'s composable body (finding #54) so the
 * "same dao instance -> same Flow instance, queried at most once" invariant can be verified on
 * plain JVM without a Compose runtime.
 *
 * Before the fix, [ManualFinanceScreen] called `dao.recent(20)` directly on every
 * recomposition (e.g. once per keystroke in the Amount/Currency/Category/Description fields),
 * producing a fresh [Flow] instance each time and causing the downstream collector to cancel
 * and re-subscribe to the underlying Room query over and over.
 */
class RecentItemsFlowCacheTest {

    private class FakeDao : PendingItemDao {
        var recentCallCount = 0
        var lastRequestedLimit: Int? = null

        override suspend fun upsert(item: PendingItem) {}
        override suspend fun pending(): List<PendingItem> = emptyList()
        override fun recent(limit: Int): Flow<List<PendingItem>> {
            recentCallCount++
            lastRequestedLimit = limit
            return flowOf(emptyList())
        }
        override suspend fun markSynced(id: String, ts: Long) {}
        override suspend fun markFailed(id: String, ts: Long, err: String) {}
        override suspend fun pruneSyncedBefore(ts: Long) {}
        override suspend fun byKind(kind: String): List<PendingItem> = emptyList()
        override suspend fun deleteById(id: String) {}
    }

    @Test
    fun flowFor_returnsSameFlowInstanceAcrossRepeatedCallsWithSameDao() {
        val dao = FakeDao()
        val cache = RecentItemsFlowCache()

        val first = cache.flowFor(dao)
        val second = cache.flowFor(dao)
        val third = cache.flowFor(dao)

        assertSame(first, second)
        assertSame(first, third)
    }

    @Test
    fun flowFor_onlyQueriesDaoOnceAcrossManyRepeatedCallsWithSameDao_simulatingKeystrokes() {
        val dao = FakeDao()
        val cache = RecentItemsFlowCache()

        // Simulate many recompositions (e.g. one per keystroke) all passing the same dao.
        repeat(20) { cache.flowFor(dao) }

        assertEquals(1, dao.recentCallCount)
        assertEquals(20, dao.lastRequestedLimit)
    }

    @Test
    fun flowFor_requeriesAndReturnsNewFlowWhenDaoInstanceChanges() {
        val daoA = FakeDao()
        val daoB = FakeDao()
        val cache = RecentItemsFlowCache()

        val flowA = cache.flowFor(daoA)
        val flowB = cache.flowFor(daoB)

        assertEquals(1, daoA.recentCallCount)
        assertEquals(1, daoB.recentCallCount)
        assertNotSame(flowA, flowB)
    }
}
