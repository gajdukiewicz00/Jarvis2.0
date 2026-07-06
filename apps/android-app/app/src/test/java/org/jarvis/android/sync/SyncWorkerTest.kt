package org.jarvis.android.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [attemptItemSync] — extracted from [SyncWorker.doWork]'s per-item loop so the
 * cancellation-safety fix (finding #33) can be verified on plain JVM, without an Android
 * [android.content.Context] / WorkManager / Room.
 *
 * The regression this guards against: a `catch (e: Exception)` block also catches
 * [CancellationException] on the JVM (it is a [RuntimeException]), so a WorkManager-initiated
 * cancellation was previously logged and recorded as an ordinary sync failure instead of
 * unwinding the coroutine immediately.
 */
class SyncWorkerTest {

    @Test
    fun attemptItemSync_rethrowsCancellationInsteadOfSwallowingIt() = runBlocking {
        var onFailedCalled = false
        var onSyncedCalled = false

        try {
            attemptItemSync(
                postBlob = { throw CancellationException("worker cancelled") },
                onSynced = { onSyncedCalled = true },
                onFailed = { onFailedCalled = true }
            )
            fail("expected CancellationException to propagate out of attemptItemSync")
        } catch (e: CancellationException) {
            // expected: cancellation must unwind the loop, not be swallowed as a failure.
        }

        assertFalse("onFailed must not run when the attempt was cancelled", onFailedCalled)
        assertFalse("onSynced must not run when the attempt was cancelled", onSyncedCalled)
    }

    @Test
    fun attemptItemSync_treatsOrdinaryExceptionAsFailureAndReturnsTrue() = runBlocking {
        var failureMessage: String? = null

        val failed = attemptItemSync(
            postBlob = { throw IOException("network unreachable") },
            onSynced = { fail("onSynced should not run for a network failure") },
            onFailed = { message -> failureMessage = message }
        )

        assertTrue("an ordinary exception must be reported as a failure", failed)
        assertEquals("network unreachable", failureMessage)
    }

    @Test
    fun attemptItemSync_marksSyncedAndReturnsFalseOnSuccessHttpCode() = runBlocking {
        var syncedCalled = false

        val failed = attemptItemSync(
            postBlob = { 202 },
            onSynced = { syncedCalled = true },
            onFailed = { fail("onFailed should not run for a 2xx response") }
        )

        assertFalse(failed)
        assertTrue(syncedCalled)
    }

    @Test
    fun attemptItemSync_marksFailedAndReturnsTrueOnNonSuccessHttpCode() = runBlocking {
        var failureMessage: String? = null

        val failed = attemptItemSync(
            postBlob = { 500 },
            onSynced = { fail("onSynced should not run for a non-2xx response") },
            onFailed = { message -> failureMessage = message }
        )

        assertTrue(failed)
        assertEquals("http=500", failureMessage)
    }
}
