package org.jarvis.desktop.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Complements the sibling AudioPlayerTest by pinning down the callback
 * *contract* on the failure path: [AudioPlayer.play] must invoke
 * onPlaybackFinished exactly once (only from the `finally` block) when the
 * supplied bytes never decode into a playable stream, and it must never open
 * real audio hardware. Unparsable / empty inputs both take the decode-failure
 * branch, so no [javax.sound.sampled.SourceDataLine] is ever opened.
 */
class AudioPlayerFinishedContractTest {

    @Test
    fun `finished callback fires exactly once on decode failure`() {
        val player = AudioPlayer()
        val startedCount = AtomicInteger(0)
        val finishedCount = AtomicInteger(0)
        val finishedLatch = CountDownLatch(1)
        player.onPlaybackStarted = { startedCount.incrementAndGet() }
        player.onPlaybackFinished = {
            finishedCount.incrementAndGet()
            finishedLatch.countDown()
        }

        player.play(byteArrayOf(7, 7, 7, 7))

        assertTrue(finishedLatch.await(5, TimeUnit.SECONDS), "onPlaybackFinished should fire")
        // Give any stray extra invocation a chance to land before asserting the count.
        Thread.sleep(200)
        assertEquals(1, startedCount.get(), "onPlaybackStarted must fire exactly once")
        assertEquals(1, finishedCount.get(), "onPlaybackFinished must fire exactly once (finally-only)")
    }

    @Test
    fun `empty audio data still completes via the finished callback`() {
        val player = AudioPlayer()
        val finishedLatch = CountDownLatch(1)
        player.onPlaybackFinished = { finishedLatch.countDown() }

        player.play(ByteArray(0))

        assertTrue(
            finishedLatch.await(5, TimeUnit.SECONDS),
            "empty input must still resolve through onPlaybackFinished without opening audio hardware"
        )
    }
}
