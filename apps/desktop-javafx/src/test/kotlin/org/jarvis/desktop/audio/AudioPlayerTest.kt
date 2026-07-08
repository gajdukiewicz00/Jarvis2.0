package org.jarvis.desktop.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [AudioPlayer.play] always runs on a background thread and only reaches a
 * real [javax.sound.sampled.SourceDataLine] (real speaker output) once the
 * supplied bytes parse as a playable audio stream. This test intentionally
 * supplies unparsable bytes so the method exercises its full
 * start-callback / decode-failure / finished-callback path without ever
 * opening real audio hardware.
 */
class AudioPlayerTest {

    @Test
    fun `play invokes started then finished callbacks even when the audio data is unparsable`() {
        val player = AudioPlayer()
        val startedLatch = CountDownLatch(1)
        val finishedLatch = CountDownLatch(1)
        player.onPlaybackStarted = { startedLatch.countDown() }
        player.onPlaybackFinished = { finishedLatch.countDown() }

        player.play(byteArrayOf(1, 2, 3, 4, 5))

        assertTrue(startedLatch.await(5, TimeUnit.SECONDS), "onPlaybackStarted should fire")
        assertTrue(finishedLatch.await(5, TimeUnit.SECONDS), "onPlaybackFinished should fire even on decode failure")
    }

    @Test
    fun `play reports a PLAYBACK_FAILED result distinct from server TTS status`() {
        val player = AudioPlayer()
        val resultLatch = CountDownLatch(1)
        val results = CopyOnWriteArrayList<PlaybackResult>()
        player.onPlaybackResult = { r ->
            results += r
            resultLatch.countDown()
        }

        // Unparsable bytes never reach a real output line, so this is a client-side
        // playback failure — reported independently of whether the gateway TTS is healthy.
        player.play(byteArrayOf(1, 2, 3, 4, 5))

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS), "onPlaybackResult should fire")
        assertEquals(PlaybackResult.PLAYBACK_FAILED, results.single())
    }

    @Test
    fun `play tolerates missing callbacks`() {
        val player = AudioPlayer()
        // No callbacks registered — must not throw synchronously or asynchronously.
        player.play(byteArrayOf(9, 9, 9))
        Thread.sleep(200)
    }
}
