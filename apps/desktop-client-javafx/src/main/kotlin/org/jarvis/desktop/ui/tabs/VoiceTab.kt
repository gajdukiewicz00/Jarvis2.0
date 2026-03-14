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
import org.jarvis.desktop.model.VoiceActionAvailability
import org.jarvis.desktop.model.VoiceEventClassifier
import org.jarvis.desktop.model.VoiceRuntimeState
import org.jarvis.desktop.model.VoiceState
import org.jarvis.desktop.model.VoiceUxStatus
import org.jarvis.desktop.runtime.DesktopRuntimeMonitor
import org.jarvis.desktop.service.SystemControlService
import org.jarvis.desktop.service.VoiceControlService
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
class VoiceTab(
    private val apiClient: ApiClient,
    private val runtimeMonitor: DesktopRuntimeMonitor
) {
    private val logger = LoggerFactory.getLogger(VoiceTab::class.java)
    
    val tab = Tab("Voice")
    private val statusLabel = Label("")
    private val guidanceLabel = Label("")
    private val deviceInfoLabel = Label("")
    private val transcriptionArea = TextArea()
    private val responseArea = TextArea()
    private val pushToTalkBtn = Button("🎤 Manual Talk")
    private val cancelBtn = Button("⏹ Stop / Cancel")
    private val toggleListeningBtn = Button("Start Always Listening")
    private val refreshDevicesBtn = Button("↻ Refresh devices")
    private val stateIndicator = Circle(10.0)
    
    private val audioRecorder = AudioRecorder()
    private val audioPlayer = AudioPlayer()
    private lateinit var voiceWebSocketClient: VoiceWebSocketClient
    private lateinit var voiceSession: VoiceSession
    lateinit var voiceControlService: VoiceControlService
        private set
    
    private var wakeWordDetector: WakeWordDetector? = null
    private var isAlwaysListening = false
    @Volatile private var previousVoiceState: VoiceRuntimeState? = null

    init {
        val content = VBox(10.0)
        content.children.add(Label("Voice Control"))
        
        // Initialize VoiceSession state machine first
        voiceSession = VoiceSession(
            onStateChange = { state, correlationId ->
                logger.info("📊 State change: {} (correlationId={})", state, correlationId)
                runtimeMonitor.consumeVoiceStatus(state.name)
                if (::voiceControlService.isInitialized) {
                    voiceControlService.onSessionStateChanged(state, correlationId)
                }
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
                runtimeMonitor.consumeVoiceStatus(state)
                if (::voiceControlService.isInitialized) {
                    voiceControlService.onConnectionStateChanged(state)
                }
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
                runtimeMonitor.recordAssistantResponse(text, action, handled)
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
        
        // Voice control service — single control surface for voice state/actions
        voiceControlService = VoiceControlService(voiceSession, voiceWebSocketClient)
        voiceControlService.addListener { vrs ->
            val events = VoiceEventClassifier.classify(previousVoiceState, vrs)
            previousVoiceState = vrs
            events.forEach { event ->
                runtimeMonitor.recordEvent(
                    source = DesktopRuntimeMonitor.EventSource.VOICE,
                    severity = mapEventSeverity(event.severity),
                    title = event.title,
                    details = event.details
                )
            }
        }
        voiceControlService.addListener { runtimeMonitor.updateVoiceRuntime(it) }
        voiceControlService.addListener { vrs -> Platform.runLater { renderVoiceStatus(vrs) } }
        voiceControlService.refreshDevices()

        // Connect immediately
        voiceWebSocketClient.connect()
        
        // State indicator + headline
        val stateRow = HBox(10.0)
        stateIndicator.fill = Color.GRAY
        val stateLabel = Label("Status:")
        stateRow.children.addAll(stateLabel, stateIndicator, statusLabel)
        content.children.add(stateRow)
        statusLabel.style = "-fx-font-weight: bold;"

        guidanceLabel.style = "-fx-font-size: 12px; -fx-text-fill: #555;"
        guidanceLabel.isWrapText = true
        content.children.add(guidanceLabel)

        // Device info row
        deviceInfoLabel.style = "-fx-font-size: 11px; -fx-text-fill: #666;"
        refreshDevicesBtn.style = "-fx-font-size: 11px;"
        refreshDevicesBtn.setOnAction { voiceControlService.refreshDevices() }
        val deviceRow = HBox(8.0)
        deviceRow.children.addAll(deviceInfoLabel, refreshDevicesBtn)
        content.children.add(deviceRow)

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
            val correlationId = voiceSession.currentCorrelationId
            if (voiceSession.state == VoiceState.LISTENING && correlationId != null) {
                logger.info("⏹️ Manual talk button released, ending session")
                voiceControlService.pushToTalkRelease()
                stopRecordingInternal()
                voiceWebSocketClient.endOfSpeech()
            }
            Platform.runLater {
                pushToTalkBtn.text = "🎤 Manual Talk"
            }
        }

        // Cancel / Stop button
        cancelBtn.prefWidth = 200.0
        cancelBtn.prefHeight = 35.0
        cancelBtn.style = "-fx-font-size: 13px; -fx-background-color: #E74C3C; -fx-text-fill: white;"
        cancelBtn.isDisable = true
        cancelBtn.setOnAction {
            logger.info("⏹ Cancel requested by user")
            voiceControlService.cancelCurrentSession()
        }

        val buttonRow = HBox(10.0)
        buttonRow.children.addAll(pushToTalkBtn, cancelBtn)
        content.children.add(buttonRow)

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
            stateIndicator.fill = Color.web(newState.getColorHex())
        }
    }

    private fun renderVoiceStatus(vrs: VoiceRuntimeState) {
        val status = VoiceUxStatus.compute(vrs)
        statusLabel.text = status.headline
        statusLabel.style = "-fx-font-weight: bold; -fx-text-fill: ${severityColor(status.severity)};"
        guidanceLabel.text = status.guidance ?: ""
        guidanceLabel.isVisible = status.guidance != null
        guidanceLabel.isManaged = status.guidance != null

        val deviceParts = mutableListOf<String>()
        vrs.inputDevice?.let { deviceParts += "Mic: ${it.name}" }
        vrs.outputDevice?.let { deviceParts += "Out: ${it.name}" }
        if (vrs.availableInputDevices.size > 1) {
            deviceParts += "(${vrs.availableInputDevices.size} inputs available)"
        }
        deviceInfoLabel.text = if (deviceParts.isEmpty()) "No audio devices detected" else deviceParts.joinToString(" • ")

        val actions = VoiceActionAvailability.from(vrs)
        pushToTalkBtn.isDisable = !(actions.canPushToTalkStart || actions.canPushToTalkRelease)
        cancelBtn.isDisable = !actions.canCancelSession
        toggleListeningBtn.isDisable = !actions.canToggleAlwaysListening
        refreshDevicesBtn.isDisable = !actions.canRefreshDevices
    }

    private fun mapEventSeverity(s: VoiceEventClassifier.Severity): DesktopRuntimeMonitor.EventSeverity =
        when (s) {
            VoiceEventClassifier.Severity.INFO -> DesktopRuntimeMonitor.EventSeverity.INFO
            VoiceEventClassifier.Severity.SUCCESS -> DesktopRuntimeMonitor.EventSeverity.SUCCESS
            VoiceEventClassifier.Severity.WARNING -> DesktopRuntimeMonitor.EventSeverity.WARNING
            VoiceEventClassifier.Severity.ERROR -> DesktopRuntimeMonitor.EventSeverity.ERROR
        }

    private fun severityColor(severity: VoiceUxStatus.Severity): String = when (severity) {
        VoiceUxStatus.Severity.INFO -> "#555"
        VoiceUxStatus.Severity.ACTIVE -> "#E74C3C"
        VoiceUxStatus.Severity.SUCCESS -> "#27AE60"
        VoiceUxStatus.Severity.WARNING -> "#F39C12"
        VoiceUxStatus.Severity.ERROR -> "#C0392B"
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
            voiceControlService.onAlwaysListeningChanged(true)
            
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
        voiceControlService.onAlwaysListeningChanged(false)
        
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
     * Delegates to VoiceControlService for state management.
     */
    private fun startManualRecording() {
        logger.info("🎤 Manual recording requested")
        
        val correlationId = voiceControlService.pushToTalkStart()
        
        if (correlationId != null) {
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
