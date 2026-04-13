package org.jarvis.desktop.audio

import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread
import org.slf4j.LoggerFactory

class AudioPlayer {
    private val logger = LoggerFactory.getLogger(AudioPlayer::class.java)
    
    var onPlaybackStarted: (() -> Unit)? = null
    var onPlaybackFinished: (() -> Unit)? = null
    
    fun play(audioData: ByteArray) {
        thread {
            try {
                onPlaybackStarted?.invoke()
                println("🔊 TTS playback started")
                
                val inputStream = ByteArrayInputStream(audioData)
                val audioInputStream = AudioSystem.getAudioInputStream(inputStream)
                val format = audioInputStream.format
                val info = DataLine.Info(SourceDataLine::class.java, format)

                if (!AudioSystem.isLineSupported(info)) {
                    System.err.println("Audio line not supported for playback")
                    onPlaybackFinished?.invoke()
                    return@thread
                }

                val line = AudioSystem.getLine(info) as SourceDataLine
                line.open(format)
                line.start()

                val buffer = ByteArray(4096)
                var bytesRead = 0
                while (audioInputStream.read(buffer).also { bytesRead = it } != -1) {
                    line.write(buffer, 0, bytesRead)
                }

                line.drain()
                line.close()
                audioInputStream.close()
                
                println("🔊 TTS playback finished")
            } catch (e: Exception) {
                logger.error("Error playing audio: {}", e.message, e)
            } finally {
                onPlaybackFinished?.invoke()
            }
        }
    }
}
