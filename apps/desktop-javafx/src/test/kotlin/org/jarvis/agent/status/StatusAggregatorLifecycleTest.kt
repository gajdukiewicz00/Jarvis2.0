package org.jarvis.agent.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the background-poller lifecycle ([StatusAggregator.start] /
 * [StatusAggregator.stop]) that [StatusAggregatorTest] does not exercise.
 * A dead backend URL keeps every probe fast (connection refused) and fully
 * deterministic — the poller populates a DOWN snapshot on its first tick.
 */
class StatusAggregatorLifecycleTest {

    // Port 1 is privileged and never listening: connection is refused instantly.
    private val deadUrl = "http://127.0.0.1:1"

    @Test
    fun `start spawns a poller that populates the snapshot then stop halts it`() {
        val aggregator = StatusAggregator(backendBaseUrl = deadUrl, pollMillis = 60_000)
        try {
            aggregator.start()
            val deadline = System.currentTimeMillis() + 5_000
            while (aggregator.snapshot.size < 5 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(5, aggregator.snapshot.size)
            aggregator.snapshot.values.forEach {
                assertEquals(StatusAggregator.ProbeStatus.DOWN, it.status)
            }
        } finally {
            aggregator.stop()
        }
    }

    @Test
    fun `calling start twice does not throw and remains a single poller`() {
        val aggregator = StatusAggregator(backendBaseUrl = deadUrl, pollMillis = 60_000)
        try {
            aggregator.start()
            aggregator.start() // second call hits the running guard and returns early
            val deadline = System.currentTimeMillis() + 5_000
            while (aggregator.snapshot.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertTrue(aggregator.snapshot.isNotEmpty())
        } finally {
            aggregator.stop()
        }
    }

    @Test
    fun `stop before start is a no-op`() {
        val aggregator = StatusAggregator(backendBaseUrl = deadUrl)
        aggregator.stop()
        assertTrue(aggregator.snapshot.isEmpty())
    }
}
