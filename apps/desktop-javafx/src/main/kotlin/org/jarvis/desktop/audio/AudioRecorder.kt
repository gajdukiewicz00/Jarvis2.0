package org.jarvis.desktop.audio

import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import javax.sound.sampled.*
import kotlin.concurrent.thread

/**
 * Audio recorder for capturing microphone input.
 * 
 * Thread-safe: the recording flag is volatile and checked in the recording loop.
 * Call stopRecording() to immediately stop audio capture and close the line.
 */
class AudioRecorder {
    private val logger = LoggerFactory.getLogger(AudioRecorder::class.java)
    private val format = AudioFormat(16000f, 16, 1, true, false) // 16kHz, 16-bit, mono, signed, little-endian
    private var targetLine: TargetDataLine? = null
    @Volatile private var recording = false
    private var recordingThread: Thread? = null

    /** Returns true if currently recording */
    val isRecording: Boolean get() = recording

    fun startRecording(
        onAudioCaptured: (ByteArray) -> Unit, 
        onError: (Exception) -> Unit,
        onChunkCaptured: ((ByteArray) -> Unit)? = null
    ) {
        if (recording) {
            logger.warn("Already recording, ignoring start request")
            return
        }

        try {
            val info = DataLine.Info(TargetDataLine::class.java, format)
            
            if (!AudioSystem.isLineSupported(info)) {
                onError(Exception("Audio line not supported"))
                return
            }

            targetLine = AudioSystem.getLine(info) as TargetDataLine
            targetLine?.open(format)
            targetLine?.start()
            recording = true

            logger.info("🎙️ Audio recording started")

            val audioData = ByteArrayOutputStream()

            recordingThread = thread(name = "AudioRecorder", isDaemon = true) {
                val buffer = ByteArray(4096)
                try {
                    while (recording && targetLine != null) {
                        val line = targetLine ?: break
                        val bytesRead = line.read(buffer, 0, buffer.size)
                        if (bytesRead > 0 && recording) {
                            val chunk = buffer.copyOfRange(0, bytesRead)
                            audioData.write(chunk)
                            onChunkCaptured?.invoke(chunk)
                        }
                    }
                    
                    logger.debug("Recording loop exited, recording={}", recording)
                    
                    // Convert to WAV format (for legacy/full file usage)
                    if (audioData.size() > 0) {
                        val wavData = convertToWav(audioData.toByteArray())
                        onAudioCaptured(wavData)
                    }
                } catch (e: InterruptedException) {
                    logger.debug("Recording thread interrupted")
                } catch (e: Exception) {
                    if (recording) {
                        logger.error("Error in recording loop", e)
                        onError(e)
                    }
                }
            }
        } catch (e: Exception) {
            recording = false
            logger.error("Failed to start recording", e)
            onError(e)
        }
    }

    /**
     * Stop recording immediately.
     * This method is thread-safe and can be called from any thread.
     */
    fun stopRecording() {
        if (!recording) return
        
        logger.info("⏹️ Stopping audio recording")
        
        // Set flag first to stop the recording loop
        recording = false
        
        // Flush + stop + close the capture line so the OS capture stream (PulseAudio/PipeWire
        // source-output / file descriptor) is released every command. Leaking it across many
        // commands is the prime suspect for "stops recording after ~N commands".
        try {
            targetLine?.flush()
            targetLine?.stop()
            targetLine?.close()
        } catch (e: Exception) {
            logger.debug("Error closing audio line", e)
        }
        targetLine = null

        // Interrupt the thread and wait for it to actually die before the next command opens a
        // new line (a lingering thread keeps reading a closed line and compounds the leak).
        try {
            recordingThread?.interrupt()
            recordingThread?.join(1500)
            if (recordingThread?.isAlive == true) {
                logger.warn("AudioRecorder thread did not exit within 1500ms")
            }
        } catch (e: Exception) {
            logger.debug("Error interrupting recording thread", e)
        }
        recordingThread = null
        
        logger.debug("Audio recording stopped")
    }

    private fun convertToWav(pcmData: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        
        // WAV header
        output.write("RIFF".toByteArray())
        writeInt(output, 36 + pcmData.size) // File size - 8
        output.write("WAVE".toByteArray())
        
        // fmt chunk
        output.write("fmt ".toByteArray())
        writeInt(output, 16) // fmt chunk size
        writeShort(output, 1) // Audio format (PCM)
        writeShort(output, 1) // Channels (mono)
        writeInt(output, 16000) // Sample rate
        writeInt(output, 32000) // Byte rate (16000 * 1 * 16/8)
        writeShort(output, 2) // Block align (1 * 16/8)
        writeShort(output, 16) // Bits per sample
        
        // data chunk
        output.write("data".toByteArray())
        writeInt(output, pcmData.size)
        output.write(pcmData)
        
        return output.toByteArray()
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }

    private fun writeShort(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }
}
