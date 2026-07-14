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
    // Optional specific input device (mixer) name. When null, the default input
    // line is used — the same mic Manual Talk opens via AudioSystem.getLine.
    private val deviceName: String? = null,
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
            // Stop actively capturing so the command recorder is the ONLY open capture stream
            // during a command — running two concurrent mic streams leaks OS capture handles and
            // is the prime suspect for "recording stops after ~N commands". The line stays OPEN
            // (cheap stop/start), so no per-command open/close churn.
            try {
                targetDataLine?.stop()
            } catch (e: Exception) {
                System.err.println("Wake detector pause stop() error: ${e.message}")
            }
            println("Wake word detector paused (capture stopped)")
        }
    }

    /**
     * Resume wake word detection.
     */
    fun resume() {
        if (currentState == ListeningState.PAUSED) {
            try {
                targetDataLine?.start()
            } catch (e: Exception) {
                System.err.println("Wake detector resume start() error: ${e.message}")
            }
            currentState = ListeningState.LISTENING
            println("Wake word detector resumed (capture restarted)")
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

        val line = openTargetLine(dataLineInfo, audioFormat)
        targetDataLine = line
        line.start()
        
        // Start audio processing thread
        audioThread = Thread {
            processMicrophoneAudio(porcupineInstance)
        }.apply {
            name = "WakeWordDetectorThread"
            isDaemon = true
            start()
        }
    }

    /**
     * Open the capture line. If a specific [deviceName] was requested, open THAT
     * mixer's [TargetDataLine] so wake-word detection can be pinned to the same
     * mic Manual Talk uses; otherwise fall back to the default input line.
     */
    private fun openTargetLine(dataLineInfo: DataLine.Info, audioFormat: AudioFormat): TargetDataLine {
        val requested = deviceName?.trim()
        if (!requested.isNullOrEmpty()) {
            val mixerInfo = AudioSystem.getMixerInfo().firstOrNull { it.name.trim() == requested }
            if (mixerInfo != null) {
                val mixer = AudioSystem.getMixer(mixerInfo)
                if (mixer.isLineSupported(dataLineInfo)) {
                    val line = mixer.getLine(dataLineInfo) as TargetDataLine
                    line.open(audioFormat)
                    return line
                }
            }
            System.err.println("Requested wake-word input device '$requested' unavailable; using default input line")
        }

        if (!AudioSystem.isLineSupported(dataLineInfo)) {
            throw IllegalStateException("Microphone not supported")
        }
        val line = AudioSystem.getLine(dataLineInfo) as TargetDataLine
        line.open(audioFormat)
        return line
    }
    
    private fun processMicrophoneAudio(porcupineInstance: Porcupine) {
        val frameLength = porcupineInstance.frameLength
        val buffer = ByteArray(frameLength * 2)  // 2 bytes per sample (16-bit)
        val pcmBuffer = ShortArray(frameLength)
        
        println("Listening for wake word... (frame length: $frameLength)")
        
        while (isRunning) {
            try {
                // While paused (a command is being recorded), don't read the mic at all — the
                // command recorder owns the capture device. Prevents two concurrent capture streams.
                if (currentState == ListeningState.PAUSED) {
                    Thread.sleep(20)
                    continue
                }

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
    
    /**
     * Structured view of a caught wake-word initialization failure: the plain
     * message, Porcupine 4.x's full [PorcupineException.getMessageStack], and the
     * parsed native error code (when present).
     */
    data class FailureDetails(
        val message: String?,
        val messageStack: List<String>,
        val nativeCode: String?
    )

    companion object {
        // Porcupine 4.x messageStack lines look like "[0] d3ff828 00000136: ...";
        // the 8-hex token ("00000136") is the native error code we surface.
        private val NATIVE_CODE_REGEX = Regex("\\b([0-9a-fA-F]{8})\\b")

        /**
         * Create detector with built-in Porcupine keywords (e.g., JARVIS in English).
         * No need to download or train models - works immediately!
         */
        fun createWithBuiltInKeywords(
            accessKey: String,
            keywords: List<BuiltInKeyword>,  // e.g., [BuiltInKeyword.JARVIS]
            sensitivity: Float = 0.5f,
            deviceName: String? = null,
            onWakeWordDetected: (Int) -> Unit
        ): WakeWordDetector {
            return WakeWordDetector(
                accessKey = accessKey,
                keywordPaths = null,
                builtInKeywords = keywords,
                sensitivity = sensitivity,
                deviceName = deviceName,
                onWakeWordDetected = onWakeWordDetected
            )
        }

        /**
         * Turn any caught [Throwable] from a start() attempt into a structured
         * [FailureDetails]. Captures the full Porcupine message stack (4.x) so the
         * cryptic "[0] ... 00000136" native detail lands in logs/diagnostics rather
         * than being shown raw to the user.
         */
        fun describeFailure(t: Throwable): FailureDetails {
            val stack: List<String> = when (t) {
                is PorcupineException -> t.messageStack?.toList().orEmpty()
                else -> emptyList()
            }
            val searchable = stack + listOfNotNull(t.message)
            return FailureDetails(
                message = t.message,
                messageStack = stack,
                nativeCode = extractNativeCode(searchable)
            )
        }

        /** Extract the first "00000136"-style native code from message/stack lines. */
        fun extractNativeCode(lines: List<String>): String? {
            for (line in lines) {
                NATIVE_CODE_REGEX.find(line)?.let { return it.groupValues[1] }
            }
            return null
        }
    }
}
