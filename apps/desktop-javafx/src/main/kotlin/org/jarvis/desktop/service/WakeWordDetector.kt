package org.jarvis.desktop.service

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.Porcupine.BuiltInKeyword
import javafx.application.Platform
import org.jarvis.desktop.config.PorcupineCompatibility
import javax.sound.sampled.*

/**
 * Wake word detector using Porcupine for "Jarvis" activation.
 * Continuously listens to microphone and triggers callback when wake word is detected.
 */
class WakeWordDetector(
    private val accessKey: String,
    private val keywordPaths: List<String>? = null,  // paths to .ppn files
    private val builtInKeywords: List<BuiltInKeyword>? = null,  // built-in keywords
    private val sensitivity: Float = 0.5f,
    private val onWakeWordDetected: (Int) -> Unit  // callback with keyword index
) {
    
    private var porcupine: Porcupine? = null

    @Volatile
    private var isRunning = false
    private var audioThread: Thread? = null
    private var targetDataLine: TargetDataLine? = null
    
    @Volatile
    private var currentState: ListeningState = ListeningState.STOPPED
    
    enum class ListeningState {
        STOPPED,
        LISTENING,
        PAUSED
    }
    
    /**
     * Start wake word detection in background thread.
     */
    fun start() {
        if (isRunning) {
            println("Wake word detector already running")
            return
        }
        
        try {
            // Ensure tmp dir is writable for Porcupine resource extraction
            val tmpDir = System.getenv("JARVIS_TMP_DIR") ?: "/tmp/jarvis-porcupine"
            try {
                val dir = java.io.File(tmpDir)
                if (!dir.exists()) dir.mkdirs()
                System.setProperty("java.io.tmpdir", dir.absolutePath)
            } catch (_: Exception) {
                // fallback: let default tmpdir be used
            }

            // Initialize Porcupine
            val builder = Porcupine.Builder()
                .setAccessKey(accessKey)
            
            val keywordCount: Int
            if (builtInKeywords != null && builtInKeywords.isNotEmpty()) {
                // Use built-in keywords
                builtInKeywords.forEach { keyword ->
                    builder.setBuiltInKeyword(keyword)
                }
                keywordCount = builtInKeywords.size
                println("Using built-in keywords: ${builtInKeywords.joinToString { it.name }}")
            } else if (keywordPaths != null && keywordPaths.isNotEmpty()) {
                // Use custom keyword paths
                keywordPaths.forEach { path ->
                    builder.setKeywordPath(path)
                }
                keywordCount = keywordPaths.size
            } else {
                throw IllegalStateException("Either keywordPaths or builtInKeywords must be provided")
            }
            
            // Set sensitivity (0.0 to 1.0)
            builder.setSensitivities(FloatArray(keywordCount) { sensitivity })
            
            porcupine = builder.build()
            
            println("Porcupine initialized with $keywordCount keywords")
            println("Sample rate: ${porcupine?.sampleRate}")
            println("Frame length: ${porcupine?.frameLength}")
            
            // Start microphone capture
            startMicrophoneCapture()
            
            isRunning = true
            currentState = ListeningState.LISTENING
            
            println("Wake word detector started")
            
        } catch (e: PorcupineException) {
            val compatibilityMessage = PorcupineCompatibility.describeInitializationFailure(e.message)
            if (compatibilityMessage != null && compatibilityMessage != e.message?.trim()) {
                System.err.println("Failed to initialize Porcupine: $compatibilityMessage")
                throw IllegalStateException(compatibilityMessage, e)
            }
            System.err.println("Failed to initialize Porcupine: ${e.message}")
            // Don't crash UI: just propagate to caller to switch to manual push-to-talk
            throw e
        } catch (e: Exception) {
            System.err.println("Failed to start wake word detector: ${e.message}")
            throw e
        }
    }
    
    /**
     * Stop wake word detection.
     */
    fun stop() {
        if (!isRunning) return
        
        isRunning = false
        currentState = ListeningState.STOPPED
        
        // Stop microphone
        targetDataLine?.stop()
        targetDataLine?.close()
        
        // Wait for thread to finish
        audioThread?.join(1000)
        
        // Clean up Porcupine
        porcupine?.delete()
        porcupine = null
        
        println("Wake word detector stopped")
    }
    
    /**
     * Pause wake word detection (keeps microphone active).
     */
    fun pause() {
        if (currentState == ListeningState.LISTENING) {
            currentState = ListeningState.PAUSED
            println("Wake word detector paused")
        }
    }
    
    /**
     * Resume wake word detection.
     */
    fun resume() {
        if (currentState == ListeningState.PAUSED) {
            currentState = ListeningState.LISTENING
            println("Wake word detector resumed")
        }
    }
    
    fun getState(): ListeningState = currentState
    
    private fun startMicrophoneCapture() {
        val porcupineInstance = porcupine ?: throw IllegalStateException("Porcupine not initialized")
        
        // Audio format: 16-bit PCM, mono, 16kHz (Porcupine requirement)
        val audioFormat = AudioFormat(
            porcupineInstance.sampleRate.toFloat(),
            16,  // bits per sample
            1,   // mono
            true,  // signed
            false  // little endian
        )
        
        val dataLineInfo = DataLine.Info(TargetDataLine::class.java, audioFormat)
        
        if (!AudioSystem.isLineSupported(dataLineInfo)) {
            throw IllegalStateException("Microphone not supported")
        }
        
        targetDataLine = AudioSystem.getLine(dataLineInfo) as TargetDataLine
        targetDataLine?.open(audioFormat)
        targetDataLine?.start()
        
        // Start audio processing thread
        audioThread = Thread {
            processMicrophoneAudio(porcupineInstance)
        }.apply {
            name = "WakeWordDetectorThread"
            isDaemon = true
            start()
        }
    }
    
    private fun processMicrophoneAudio(porcupineInstance: Porcupine) {
        val frameLength = porcupineInstance.frameLength
        val buffer = ByteArray(frameLength * 2)  // 2 bytes per sample (16-bit)
        val pcmBuffer = ShortArray(frameLength)
        
        println("Listening for wake word... (frame length: $frameLength)")
        
        while (isRunning) {
            try {
                // Read audio from microphone
                val bytesRead = targetDataLine?.read(buffer, 0, buffer.size) ?: -1
                
                if (bytesRead <= 0) {
                    Thread.sleep(10)
                    continue
                }
                
                // Convert bytes to shorts (16-bit PCM)
                for (i in 0 until frameLength) {
                    pcmBuffer[i] = ((buffer[i * 2 + 1].toInt() shl 8) or (buffer[i * 2].toInt() and 0xFF)).toShort()
                }
                
                // Process only if in LISTENING state
                if (currentState == ListeningState.LISTENING) {
                    val keywordIndex = porcupineInstance.process(pcmBuffer)
                    
                    if (keywordIndex >= 0) {
                        println("Wake word detected! Keyword index: $keywordIndex")
                        
                        // Pause detection during command processing
                        pause()
                        
                        // Trigger callback on JavaFX thread
                        Platform.runLater {
                            onWakeWordDetected(keywordIndex)
                        }
                    }
                }
                
            } catch (e: Exception) {
                if (isRunning) {
                    System.err.println("Error processing audio: ${e.message}")
                }
            }
        }
    }
    
    companion object {
        /**
         * Create detector with built-in Porcupine keywords (e.g., JARVIS in English).
         * No need to download or train models - works immediately!
         */
        fun createWithBuiltInKeywords(
            accessKey: String,
            keywords: List<BuiltInKeyword>,  // e.g., [BuiltInKeyword.JARVIS]
            sensitivity: Float = 0.5f,
            onWakeWordDetected: (Int) -> Unit
        ): WakeWordDetector {
            return WakeWordDetector(
                accessKey = accessKey,
                keywordPaths = null,
                builtInKeywords = keywords,
                sensitivity = sensitivity,
                onWakeWordDetected = onWakeWordDetected
            )
        }
    }
}
