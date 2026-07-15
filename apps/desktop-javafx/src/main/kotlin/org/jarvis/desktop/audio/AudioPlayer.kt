package org.jarvis.desktop.audio

import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread
import org.slf4j.LoggerFactory

/**
 * Outcome of a local TTS playback attempt. Lets the UI tell a broken/absent local
 * output device (client-side) apart from the gateway advertising TTS unavailable
 * (server-side) — the two must stay independently accurate.
 */
enum class PlaybackResult { SUCCESS, NO_OUTPUT_DEVICE, PLAYBACK_FAILED }

class AudioPlayer {
    private val logger = LoggerFactory.getLogger(AudioPlayer::class.java)

    var onPlaybackStarted: (() -> Unit)? = null
    var onPlaybackFinished: (() -> Unit)? = null

    /**
     * Reports the playback outcome (output-device selected / success / failure). Callers can
     * surface NO_OUTPUT_DEVICE / PLAYBACK_FAILED as a client-side playback status distinct from
     * the server TTS status. No audio/transcript is ever logged — only device + result.
     */
    var onPlaybackResult: ((PlaybackResult) -> Unit)? = null

    fun play(audioData: ByteArray) {
        thread {
            var result = PlaybackResult.PLAYBACK_FAILED
            try {
                onPlaybackStarted?.invoke()
                val outputDevice = runCatching { AudioSystem.getMixer(null)?.mixerInfo?.name }.getOrNull()
                logger.info("🔊 TTS playback started ({} bytes, outputDevice={})",
                    audioData.size, outputDevice ?: "default")

                val audioInputStream = AudioSystem.getAudioInputStream(ByteArrayInputStream(audioData))
                val format = audioInputStream.format
                val info = DataLine.Info(SourceDataLine::class.java, format)

                if (!AudioSystem.isLineSupported(info)) {
                    logger.warn("🔇 No usable output device: audio line unsupported for format {}", format)
                    result = PlaybackResult.NO_OUTPUT_DEVICE
                    audioInputStream.close()
                    return@thread
                }

                val line = AudioSystem.getLine(info) as SourceDataLine
                try {
                    line.open(format)
                    line.start()
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (audioInputStream.read(buffer).also { bytesRead = it } != -1) {
                        line.write(buffer, 0, bytesRead)
                    }
                    line.drain()
                } finally {
                    line.close()
                    audioInputStream.close()
                }
                result = PlaybackResult.SUCCESS
                logger.info("🔊 TTS playback finished OK")
            } catch (e: Exception) {
                logger.error("🔇 TTS playback failed: {}", e.message, e)
                result = PlaybackResult.PLAYBACK_FAILED
            } finally {
                onPlaybackResult?.invoke(result)
                onPlaybackFinished?.invoke()
            }
        }
    }
}
