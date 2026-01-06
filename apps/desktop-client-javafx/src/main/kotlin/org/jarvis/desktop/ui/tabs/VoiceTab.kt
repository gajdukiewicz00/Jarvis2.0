package org.jarvis.desktop.ui.tabs

import javafx.application.Platform
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.audio.AudioPlayer
import org.jarvis.desktop.audio.AudioRecorder
import org.jarvis.desktop.model.VoiceState
import org.jarvis.desktop.service.SystemControlService
import org.jarvis.desktop.service.VoiceSession
import org.jarvis.desktop.service.VoiceWebSocketClient
import org.jarvis.desktop.service.WakeWordDetector
import ai.picovoice.porcupine.Porcupine.BuiltInKeyword
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Voice control tab with proper session state machine.
 * 
 * Key features:
 * - State machine ensures proper transitions and cleanup
 * - Recording stops IMMEDIATELY when final transcript is received
 * - Cooldown period after TTS prevents background noise triggers
 * - Noise filtering ignores short/filler transcripts silently
 */
class VoiceTab(private val apiClient: ApiClient) {
    private val logger = LoggerFactory.getLogger(VoiceTab::class.java)
    
    val tab = Tab("Voice")
    private val statusLabel = Label("")
    private val transcriptionArea = TextArea()
    private val responseArea = TextArea()
    private val pushToTalkBtn = Button("🎤 Manual Talk")
    private val toggleListeningBtn = Button("Start Always Listening")
    private val stateIndicator = Circle(10.0)
    
    private val audioRecorder = AudioRecorder()
    private val audioPlayer = AudioPlayer()
    private lateinit var voiceWebSocketClient: VoiceWebSocketClient
    private lateinit var voiceSession: VoiceSession
    
    private var wakeWordDetector: WakeWordDetector? = null
    private var isAlwaysListening = false

    init {
        val content = VBox(10.0)
        content.children.add(Label("Voice Control"))
        
        // Initialize VoiceSession state machine first
        voiceSession = VoiceSession(
            onStateChange = { state, correlationId ->
                logger.info("📊 State change: {} (correlationId={})", state, correlationId)
                Platform.runLater { updateState(state) }
            },
            onStartRecording = {
                startRecordingInternal()
            },
            onStopRecording = {
                stopRecordingInternal()
            },
            onSendEndOfSpeech = { correlationId ->
                voiceWebSocketClient.endOfSpeech()
            },
            onEnableWakeWord = {
                if (isAlwaysListening) {
                    wakeWordDetector?.resume()
                    logger.info("🎧 Wake word detection resumed")
                }
            },
            onDisableWakeWord = {
                wakeWordDetector?.pause()
                logger.info("🔇 Wake word detection paused")
            },
            onPauseMedia = {
                // Pause media playback when wake word is detected for clearer audio
                SystemControlService.pauseMediaStatic()
            },
            onResumeMedia = {
                // Resume media playback after command is processed
                SystemControlService.resumeMediaStatic()
            },
            onSpeakTimeout = {
                // Request TTS for timeout message: "Sir, I couldn't hear you"
                // Send a special request to get the timeout phrase from server
                logger.info("🔇 Speaking timeout message")
                voiceWebSocketClient.requestTimeoutPhrase()
            },
            onSessionError = { reason, error ->
                logger.error("❌ Session error: {}", reason, error)
                Platform.runLater {
                    transcriptionArea.appendText("Error: $reason\n")
                }
            }
        )
        
        // Initialize WebSocket Client with session-aware callbacks
        voiceWebSocketClient = VoiceWebSocketClient(
            onStateChange = { state ->
                logger.debug("WebSocket State: {}", state)
                if (state == "CONNECTED") {
                    Platform.runLater { statusLabel.text = "Connected" }
                } else if (state == "DISCONNECTED") {
                    Platform.runLater { statusLabel.text = "Disconnected" }
                    // Cancel any active session on disconnect
                    voiceSession.cancelSession("WebSocket disconnected")
                }
            },
            onTranscript = { text, isFinal, correlationId ->
                Platform.runLater {
                    if (isFinal) {
                        transcriptionArea.appendText("$text\n")
                        // CRITICAL: Notify session about final transcript to stop recording
                        voiceSession.onFinalTranscript(text, correlationId)
                    }
                }
            },
            onResponse = { text, action, handled ->
                Platform.runLater {
                    responseArea.appendText("Jarvis: $text\n")
                }
            },
            onAudioReceived = { audioData ->
                // Notify session that TTS is starting before playing
                voiceSession.onTtsPlaybackStarted()
                audioPlayer.play(audioData)
            }
        )
        
        // Setup TTS playback callbacks for session state machine
        audioPlayer.onPlaybackStarted = {
            logger.info("🔊 TTS playback started")
            voiceWebSocketClient.isSendingAllowed = false
        }
        audioPlayer.onPlaybackFinished = {
            logger.info("🔊 TTS playback finished")
            voiceSession.onTtsPlaybackFinished()
        }
        
        // Connect immediately
        voiceWebSocketClient.connect()
        
        // State indicator row
        val stateRow = HBox(10.0)
        stateIndicator.fill = Color.GRAY
        val stateLabel = Label("Status:")
        stateRow.children.addAll(stateLabel, stateIndicator, statusLabel)
        content.children.add(stateRow)
        
        statusLabel.style = "-fx-font-weight: bold;"
        updateState(VoiceState.IDLE)

        // Instructions
        val instructions = Label("""
            • Click "Start Always Listening" to activate wake-word detection
            • Say "Jarvis" to start recording  
            • Or use Manual Talk button (push-to-talk)
            • Recording stops automatically when you finish speaking
        """.trimIndent())
        instructions.style = "-fx-font-size: 12px; -fx-text-fill: gray;"
        content.children.add(instructions)

        // Always Listening toggle button
        toggleListeningBtn.prefWidth = 250.0
        toggleListeningBtn.prefHeight = 40.0
        toggleListeningBtn.style = "-fx-font-size: 14px; -fx-background-color: #4A90E2; -fx-text-fill: white;"
        
        toggleListeningBtn.setOnAction {
            toggleAlwaysListening()
        }
        
        content.children.add(toggleListeningBtn)
        
        // Separator
        content.children.add(Separator())

        // Push-to-Talk button (manual mode)
        pushToTalkBtn.prefWidth = 200.0
        pushToTalkBtn.prefHeight = 50.0
        pushToTalkBtn.style = "-fx-font-size: 14px;"
        
        pushToTalkBtn.setOnMousePressed {
            startManualRecording()
        }
        
        pushToTalkBtn.setOnMouseReleased {
            // For manual mode, release triggers end of speech
            // Session will handle stopping recording
            val correlationId = voiceSession.currentCorrelationId
            if (voiceSession.state == VoiceState.LISTENING && correlationId != null) {
                logger.info("⏹️ Manual talk button released, ending session")
                stopRecordingInternal()
                voiceWebSocketClient.endOfSpeech()
            }
            Platform.runLater {
                pushToTalkBtn.text = "🎤 Manual Talk"
            }
        }
        
        content.children.add(pushToTalkBtn)

        // Transcription display
        val transcriptionLabel = Label("Transcription:")
        content.children.add(transcriptionLabel)
        
        transcriptionArea.prefHeight = 150.0
        transcriptionArea.isWrapText = true
        transcriptionArea.isEditable = false
        content.children.add(transcriptionArea)

        // Response display
        val responseLabel = Label("Response:")
        content.children.add(responseLabel)
        
        responseArea.prefHeight = 150.0
        responseArea.isWrapText = true
        responseArea.isEditable = false
        content.children.add(responseArea)

        tab.content = content
    }

    private fun updateState(newState: VoiceState) {
        Platform.runLater {
            statusLabel.text = newState.getDisplayText()
            stateIndicator.fill = Color.web(newState.getColorHex())
        }
    }

    private fun toggleAlwaysListening() {
        if (isAlwaysListening) {
            stopAlwaysListening()
        } else {
            startAlwaysListening()
        }
    }

    private fun startAlwaysListening() {
        try {
            val accessKey = System.getenv("PORCUPINE_ACCESS_KEY")
            
            if (accessKey == null || accessKey.isBlank()) {
                Platform.runLater {
                    val alert = Alert(Alert.AlertType.WARNING)
                    alert.title = "Wake Word Detection Unavailable"
                    alert.headerText = "PORCUPINE_ACCESS_KEY not set"
                    alert.contentText = """
                        Wake word detection requires a Porcupine access key.
                        
                        To enable this feature:
                        1. Get a free access key from https://console.picovoice.ai
                        2. Set environment variable: export PORCUPINE_ACCESS_KEY="your-key"
                        3. Restart the application
                        
                        You can still use "Manual Talk" button for voice commands.
                    """.trimIndent()
                    alert.showAndWait()
                }
                return
            }
            
            // Try to use custom Russian model first, fallback to built-in English "jarvis"
            val modelPath = javaClass.getResource("/models/jarvis_ru.ppn")?.path
            
            wakeWordDetector = if (modelPath != null && File(modelPath).exists()) {
                logger.info("Using custom Russian model: {}", modelPath)
                WakeWordDetector(
                    accessKey = accessKey,
                    keywordPaths = listOf(modelPath),
                    onWakeWordDetected = { keywordIndex ->
                        onWakeWordDetected(keywordIndex)
                    }
                )
            } else {
                // Fallback: Use built-in English "jarvis" keyword
                logger.info("Custom Russian model not found - using built-in English 'jarvis' keyword")
                try {
                    val detector = WakeWordDetector.createWithBuiltInKeywords(
                        accessKey = accessKey,
                        keywords = listOf(BuiltInKeyword.JARVIS),
                        onWakeWordDetected = { keywordIndex ->
                            onWakeWordDetected(keywordIndex)
                        }
                    )
                    
                    // Show info message that English model is being used
                    Platform.runLater {
                        val alert = Alert(Alert.AlertType.INFORMATION)
                        alert.title = "Using English Wake Word"
                        alert.headerText = "Russian model not found - using English 'Jarvis'"
                        alert.contentText = """
                            The custom Russian wake word model is not available.
                            
                            Currently using: Built-in English "Jarvis" keyword
                            (Say "Jarvis" to activate)
                        """.trimIndent()
                        alert.showAndWait()
                    }
                    
                    detector
                } catch (e: Exception) {
                    logger.error("Failed to initialize built-in keyword", e)
                    Platform.runLater {
                        val alert = Alert(Alert.AlertType.WARNING)
                        alert.title = "Wake Word Detection Unavailable"
                        alert.contentText = "Wake word detection is not available. Use 'Manual Talk' button instead."
                        alert.showAndWait()
                    }
                    return
                }
            }
            
            wakeWordDetector?.start()
            isAlwaysListening = true
            voiceSession.enableAlwaysListening()
            
            Platform.runLater {
                toggleListeningBtn.text = "Stop Always Listening"
                toggleListeningBtn.style = "-fx-font-size: 14px; -fx-background-color: #E74C3C; -fx-text-fill: white;"
            }
            
            logger.info("🎧 Always listening mode activated")
            
        } catch (e: Exception) {
            logger.error("Failed to start wake word detection", e)
            Platform.runLater {
                val alert = Alert(Alert.AlertType.ERROR)
                alert.title = "Wake Word Detection Error"
                alert.headerText = "Failed to start wake word detection"
                alert.contentText = """
                    Error: ${e.message}
                    
                    You can still use "Manual Talk" button for voice commands.
                """.trimIndent()
                alert.showAndWait()
            }
        }
    }

    private fun stopAlwaysListening() {
        wakeWordDetector?.stop()
        wakeWordDetector = null
        isAlwaysListening = false
        voiceSession.disableAlwaysListening()
        
        Platform.runLater {
            toggleListeningBtn.text = "Start Always Listening"
            toggleListeningBtn.style = "-fx-font-size: 14px; -fx-background-color: #4A90E2; -fx-text-fill: white;"
        }
        
        logger.info("🔇 Always listening mode deactivated")
    }

    /**
     * Called when Porcupine detects the wake word.
     * Starts a new voice session via the state machine.
     */
    private fun onWakeWordDetected(keywordIndex: Int) {
        logger.info("🎤 Wake word detected! (keyword index: {})", keywordIndex)
        
        // Start new session through state machine - VoiceSession generates the unified correlationId
        val correlationId = voiceSession.startSession()
        
        if (correlationId != null) {
            // Pass the unified correlationId to WebSocket client
            voiceWebSocketClient.startCommand(correlationId)
            logger.info("🎤 Voice session started: correlationId={}", correlationId)
        } else {
            logger.warn("⚠️ Could not start voice session (state machine rejected)")
        }
    }

    /**
     * Start manual recording when push-to-talk button is pressed.
     */
    private fun startManualRecording() {
        logger.info("🎤 Manual recording requested")
        
        // Start session through state machine - VoiceSession generates the unified correlationId
        val correlationId = voiceSession.startSession()
        
        if (correlationId != null) {
            // Pass the unified correlationId to WebSocket client
            voiceWebSocketClient.startCommand(correlationId)
            
            Platform.runLater {
                pushToTalkBtn.text = "🔴 Recording..."
            }
            
            logger.info("🎤 Manual recording started: correlationId={}", correlationId)
        } else {
            logger.warn("⚠️ Could not start manual recording (state machine rejected)")
        }
    }

    /**
     * Internal method to start audio recording and streaming.
     * Called by VoiceSession when transitioning to LISTENING state.
     */
    private fun startRecordingInternal() {
        logger.info("🎙️ Starting audio recording and streaming")
        
        // Enable audio sending
        voiceWebSocketClient.isSendingAllowed = true
        
        audioRecorder.startRecording(
            onAudioCaptured = { 
                // Legacy callback, not used in streaming mode
            },
            onError = { error ->
                logger.error("Audio recording error", error)
                voiceSession.cancelSession("Audio recording error: ${error.message}", error)
            },
            onChunkCaptured = { chunk ->
                // Only log periodically to avoid spam
                if (voiceWebSocketClient.isSendingAllowed) {
                    voiceWebSocketClient.sendAudio(chunk)
                }
            }
        )
    }

    /**
     * Internal method to stop audio recording.
     * Called by VoiceSession when transitioning out of LISTENING state.
     */
    private fun stopRecordingInternal() {
        logger.info("⏹️ Stopping audio recording")
        
        // Disable audio sending FIRST
        voiceWebSocketClient.isSendingAllowed = false
        
        // Stop the recorder
        audioRecorder.stopRecording()
    }

    fun cleanup() {
        logger.info("🧹 Cleaning up VoiceTab resources")
        stopAlwaysListening()
        audioRecorder.stopRecording()
        voiceWebSocketClient.disconnect()
        voiceSession.shutdown()
    }
}
