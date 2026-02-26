package org.jarvis.mobile.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import android.util.Log

class AudioStreamer(private val serverUrl: String) {
    companion object {
        private const val TAG = "AudioStreamer"
    }
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private var isRecording = false

    @SuppressLint("MissingPermission")
    fun startStreaming() {
        isRecording = true
        Thread {
            val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
            recorder.startRecording()

            try {
                val url = URL("$serverUrl/api/v1/voice/transcribe/stream")
                val connection = url.openConnection() as HttpURLConnection
                connection.doOutput = true
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.setChunkedStreamingMode(bufferSize)

                val outputStream: OutputStream = connection.outputStream
                val buffer = ByteArray(bufferSize)

                while (isRecording) {
                    val read = recorder.read(buffer, 0, bufferSize)
                    if (read > 0) {
                        outputStream.write(buffer, 0, read)
                    }
                }

                outputStream.close()
                val responseCode = connection.responseCode
                // Handle response if needed
            } catch (e: Exception) {
                Log.e(TAG, "Audio streaming error", e)
            } finally {
                recorder.stop()
                recorder.release()
            }
        }.start()
    }

    fun stopStreaming() {
        isRecording = false
    }
}
