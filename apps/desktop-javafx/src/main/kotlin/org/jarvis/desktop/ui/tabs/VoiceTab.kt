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
import org.jarvis.desktop.config.PorcupineAccessKeyResolver
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
import org.jarvis.desktop.service.AccessKeyValidation
import org.jarvis.desktop.service.AccessKeyValidationResult
import org.jarvis.desktop.service.CustomModelInfo
import org.jarvis.desktop.service.StaticInitInfo
import org.jarvis.desktop.service.WakeWordAttempt
import org.jarvis.desktop.service.WakeWordAttemptResult
import org.jarvis.desktop.service.WakeWordDetector
import org.jarvis.desktop.service.WakeWordInitOutcome
import org.jarvis.desktop.service.WakeWordInitializer
import org.jarvis.desktop.service.WakeWordInputDevice
import org.jarvis.desktop.service.WakeWordInputDevices
import org.jarvis.desktop.service.WakeWordMode
import org.jarvis.desktop.service.WakeWordOutcomeUi
import ai.picovoice.porcupine.Porcupine.BuiltInKeyword
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.prefs.Preferences

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
    private val testWakeWordBtn = Button("Test Wake Word Setup")
    private val refreshDevicesBtn = Button("↻ Refresh devices")
    private val wakeInputDeviceCombo = ComboBox<String>()
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

    // User's explicit wake-word microphone choice from the dropdown (null == "Auto").
    // A volatile shadow so buildWakeWordInitializer can read it off the FX thread
    // (e.g. the background diagnostics probe) without touching a JavaFX property.
    @Volatile private var wakeInputDeviceChoice: String? = null
    // Suppresses persistence while the combo is being repopulated programmatically.
    @Volatile private var suppressComboPersist = false

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
                // Do NOT append session errors ("Cancelled by user", "Always-listening disabled",
                // "WebSocket disconnected", ...) into the transcript buffer — they are not spoken
                // text and previously leaked in as if the user had said them. Log only; the status
                // is reflected by the state-machine's onStateChange handler.
                logger.error("❌ Session error: {}", reason, error)
            },
            voiceTransportReady = {
                voiceWebSocketClient.isConnected
            },
            sessionDiagnostics = {
                "recorderActive=${audioRecorder.isRecording}, " +
                    "sendingAllowed=${voiceWebSocketClient.isSendingAllowed}, " +
                    "wsConnected=${voiceWebSocketClient.isConnected}, " +
                    "wakeWordState=${wakeWordDetector?.getState()}, " +
                    "alwaysListening=$isAlwaysListening"
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
                    voiceSession.cancelSession("WebSocket disconnected")
                }
            },
            onTranscript = { text, isFinal, correlationId ->
                Platform.runLater {
                    if (isFinal) {
                        transcriptionArea.appendText("$text\n")
                        voiceSession.onFinalTranscript(text, correlationId)
                    }
                }
            },
            onResponse = { text, action, handled ->
                runtimeMonitor.recordAssistantResponse(text, action, handled)
                // A RESPONSE arrived — if no TTS audio follows shortly, recover as text-only
                // instead of hanging in PROCESSING until the timeout.
                voiceSession.onResponseReceived()
                // If the user's command WAS an explicit media pause/stop, cancel the session's
                // auto-resume so media stays paused (otherwise playerctl play ~1.5s later undoes it).
                val upper = action?.uppercase().orEmpty()
                if (upper == "PAUSE" || upper == "STOP" || upper == "MEDIA_PAUSE") {
                    SystemControlService.clearPausedByUs()
                }
                Platform.runLater {
                    responseArea.appendText("Jarvis: $text\n")
                }
            },
            onAudioReceived = { audioData ->
                voiceSession.onTtsPlaybackStarted()
                audioPlayer.play(audioData)
            },
            onSttStatusChanged = { available, reason ->
                logger.info("STT status changed: available={}, reason={}", available, reason)
                if (::voiceControlService.isInitialized) {
                    voiceControlService.onSttAvailabilityChanged(available, reason)
                }
            },
            onTtsStatusChanged = { available, reason ->
                logger.info("TTS status changed: available={}, reason={}", available, reason)
                if (::voiceControlService.isInitialized) {
                    voiceControlService.onTtsAvailabilityChanged(available, reason)
                }
            },
            onProtocolError = { code, message ->
                // Diagnostics only — protocol ERROR frames (e.g. END_NOT_ALLOWED) are never
                // rendered as a "Jarvis: …" response. STT/TTS availability is already handled
                // via onSttStatusChanged/onTtsStatusChanged; everything else is just logged.
                logger.debug("Voice protocol error (not surfaced as a response): code={}, message={}", code, message)
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
        audioPlayer.onPlaybackResult = { result ->
            // Client-side playback outcome, kept independent of the server TTS status: a healthy
            // gateway can still fail to speak if the local output device is missing/broken.
            when (result) {
                org.jarvis.desktop.audio.PlaybackResult.SUCCESS ->
                    logger.debug("TTS playback result: spoke through the default output device")
                org.jarvis.desktop.audio.PlaybackResult.NO_OUTPUT_DEVICE ->
                    logger.warn("TTS playback result: NO_OUTPUT_DEVICE — no usable local speaker/line")
                org.jarvis.desktop.audio.PlaybackResult.PLAYBACK_FAILED ->
                    logger.warn("TTS playback result: PLAYBACK_FAILED — local audio playback error")
            }
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
        refreshDevicesBtn.setOnAction {
            voiceControlService.refreshDevices()
            refreshWakeInputDeviceChoices()
        }
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
        toggleListeningBtn.style = TOGGLE_START_STYLE

        toggleListeningBtn.setOnAction {
            toggleAlwaysListening()
        }

        // Wake-word setup diagnostics (dry probe; never blocks the FX thread).
        testWakeWordBtn.style = "-fx-font-size: 12px;"
        testWakeWordBtn.setOnAction {
            runWakeWordDiagnostics()
        }

        val toggleRow = HBox(10.0)
        toggleRow.children.addAll(toggleListeningBtn, testWakeWordBtn)
        content.children.add(toggleRow)

        // Wake-word microphone selector. "Auto" lets the initializer pick the best
        // real mic (playback/output devices are already filtered out); choosing a
        // specific device pins wake-word capture to it and it is tried first.
        wakeInputDeviceCombo.promptText = AUTO_DEVICE_LABEL
        wakeInputDeviceCombo.style = "-fx-font-size: 11px;"
        wakeInputDeviceCombo.prefWidth = 260.0
        wakeInputDeviceCombo.setOnAction {
            if (suppressComboPersist) return@setOnAction
            val value = wakeInputDeviceCombo.value
            wakeInputDeviceChoice = value?.takeIf { it.isNotBlank() && it != AUTO_DEVICE_LABEL }
            persistWakeInputDeviceChoice(wakeInputDeviceChoice)
        }
        val wakeDeviceRow = HBox(8.0)
        val wakeDeviceLabel = Label("Wake Word Input Device:")
        wakeDeviceLabel.style = "-fx-font-size: 11px; -fx-text-fill: #666;"
        wakeDeviceRow.children.addAll(wakeDeviceLabel, wakeInputDeviceCombo)
        content.children.add(wakeDeviceRow)
        refreshWakeInputDeviceChoices()

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
        // Derive the toggle LABEL from the authoritative runtime flag, not the imperative
        // shadow (isAlwaysListening) that start/stopAlwaysListening flip. Any out-of-band
        // change (WS drop, session reset, disableAlwaysListening) now reconciles the label so
        // it can never read "Stop Always Listening" while always-listening is actually off.
        isAlwaysListening = vrs.alwaysListeningActive
        toggleListeningBtn.text =
            if (vrs.alwaysListeningActive) "Stop Always Listening" else "Start Always Listening"
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
        val outcome = try {
            buildWakeWordInitializer().initialize()
        } catch (e: Exception) {
            logger.error("Unexpected error initializing wake word detection", e)
            null
        }

        when (outcome) {
            is WakeWordInitOutcome.Enabled -> applyEnabledOutcome(outcome)
            is WakeWordInitOutcome.Disabled -> applyDisabledOutcome(outcome)
            null -> applyInitErrorState()
        }
    }

    /**
     * On success: adopt the started detector as the live handle, flip the
     * authoritative always-listening state, and (only when we fell back to the
     * built-in engine because the custom model was incompatible) tell the user.
     */
    private fun applyEnabledOutcome(outcome: WakeWordInitOutcome.Enabled) {
        wakeWordDetector = outcome.handle as WakeWordDetector
        isAlwaysListening = true
        voiceSession.enableAlwaysListening()
        voiceControlService.onAlwaysListeningChanged(true)
        logger.info("🎧 Wake word enabled: mode={}, device={}", outcome.mode, outcome.device?.name)
        logger.info("Wake word diagnostics: {}", outcome.diagnostics.toJson())

        Platform.runLater {
            toggleListeningBtn.text = WakeWordOutcomeUi.buttonLabel(outcome)
            toggleListeningBtn.style = TOGGLE_STOP_STYLE
            if (WakeWordOutcomeUi.shouldShowCustomFallbackNotice(outcome)) {
                val alert = Alert(Alert.AlertType.INFORMATION)
                alert.title = "Using Built-in Wake Word"
                alert.headerText = "Russian model incompatible - using built-in Jarvis"
                alert.contentText = "Custom Russian wake-word model is incompatible with current " +
                    "Porcupine runtime. Falling back to built-in Jarvis."
                alert.showAndWait()
            }
        }
    }

    /**
     * SAFE STATE RECOVERY on failure: no live detector, always-listening OFF, the
     * session is NOT put into always-listening, Manual Talk stays usable, and the
     * WebSocket/STT are untouched. The raw native stack goes to the LOG only; the
     * dialog shows a readable summary.
     */
    private fun applyDisabledOutcome(outcome: WakeWordInitOutcome.Disabled) {
        wakeWordDetector = null
        isAlwaysListening = false
        voiceControlService.onAlwaysListeningChanged(false)
        logger.error("Wake word disabled ({}). Diagnostics: {}", outcome.reason, outcome.diagnostics.toJson())

        val dialogText = WakeWordOutcomeUi.disabledDialogText(outcome.diagnostics, outcome.reason)
        Platform.runLater {
            toggleListeningBtn.text = WakeWordOutcomeUi.buttonLabel(outcome)
            toggleListeningBtn.style = TOGGLE_START_STYLE
            val alert = Alert(Alert.AlertType.WARNING)
            alert.title = "Wake Word Detection Unavailable"
            alert.headerText = "Wake word detection could not start"
            alert.contentText = dialogText
            alert.showAndWait()
        }
    }

    /** Fallback for an unexpected exception while merely building the initializer. */
    private fun applyInitErrorState() {
        wakeWordDetector = null
        isAlwaysListening = false
        voiceControlService.onAlwaysListeningChanged(false)
        Platform.runLater {
            toggleListeningBtn.text = WakeWordOutcomeUi.START_LABEL
            toggleListeningBtn.style = TOGGLE_START_STYLE
            val alert = Alert(Alert.AlertType.ERROR)
            alert.title = "Wake Word Detection Error"
            alert.headerText = "Failed to start wake word detection"
            alert.contentText = "Wake word detection failed to initialize.\nManual Talk still works."
            alert.showAndWait()
        }
    }

    /**
     * Assemble the pure [WakeWordInitializer] with production seams: the `attempt`
     * seam builds AND starts a real [WakeWordDetector] for the requested mode/device
     * and converts any Throwable into a captured [WakeWordAttemptResult.Failure]
     * (message + Porcupine 4.x message stack + native code). No native calls happen
     * inside the initializer itself.
     */
    private fun buildWakeWordInitializer(): WakeWordInitializer {
        val accessKey = PorcupineAccessKeyResolver.resolve()
        val accessKeyPresent = !accessKey.isNullOrBlank()
        val accessKeyLooksValidFormat = looksLikeValidKeyFormat(accessKey)
        logAccessKeyShape(accessKey, accessKeyPresent)
        val customModel = buildCustomModelInfo()
        val format = WakeWordInputDevices.PORCUPINE_FORMAT
        // Classify enumerated devices: real mics come back in `accepted` (playback/
        // output devices like "alsa_playback.java [default]" land in `rejected`).
        val classification = WakeWordInputDevices.listWithClassification(format)
        val defaultDeviceName = WakeWordInputDevices.defaultDeviceName(format)
        // Try the user-selected (or last-persisted-working) mic first, else the
        // preferred order the classifier already produced.
        val devices = orderForSelection(classification.accepted, selectedWakeDeviceName())
        // Validate the access key SEPARATELY from opening a mic — building a
        // Porcupine engine validates the key and needs no microphone, so this tells
        // "key invalid" apart from "no real mic".
        val validateAccessKey: () -> AccessKeyValidationResult = {
            val key = accessKey
            if (key.isNullOrBlank()) {
                AccessKeyValidationResult(AccessKeyValidation.UNKNOWN, "no access key")
            } else {
                WakeWordDetector.validateAccessKey(key)
            }
        }
        // Higher default sensitivity (0.65) makes "Jarvis" trigger more reliably on
        // quieter / accented speech. Tune via JARVIS_WAKE_SENSITIVITY.
        val wakeSensitivity =
            System.getenv("JARVIS_WAKE_SENSITIVITY")?.toFloatOrNull()?.coerceIn(0.0f, 1.0f) ?: 0.65f

        val attempt: (WakeWordAttempt) -> WakeWordAttemptResult = { request ->
            try {
                val detector = buildDetector(request, accessKey.orEmpty(), customModel, wakeSensitivity)
                detector.start()
                WakeWordAttemptResult.Success(detector)
            } catch (e: Throwable) {
                val details = WakeWordDetector.describeFailure(e)
                logger.warn(
                    "Wake word attempt failed: mode={}, device={}, code={}, msg={}",
                    request.mode, request.device?.name, details.nativeCode, details.message
                )
                WakeWordAttemptResult.Failure(
                    exceptionClass = e.javaClass.simpleName,
                    message = details.message,
                    messageStack = details.messageStack,
                    nativeCode = details.nativeCode
                )
            }
        }

        return WakeWordInitializer(
            accessKeyPresent = accessKeyPresent,
            customModel = customModel,
            devices = devices,
            attempt = attempt,
            staticInfo = StaticInitInfo(
                porcupineVersion = PORCUPINE_RUNTIME_VERSION,
                osName = System.getProperty("os.name").orEmpty(),
                osArch = System.getProperty("os.arch").orEmpty(),
                javaVersion = System.getProperty("java.version").orEmpty(),
                manualTalkDevice = defaultDeviceName,
                defaultDevice = defaultDeviceName
            ),
            persistDevice = { device -> persistWakeInputDevice(device) },
            stopProbe = { handle -> (handle as? WakeWordDetector)?.stop() },
            accessKeyLooksValidFormat = accessKeyLooksValidFormat,
            rejectedDevices = classification.rejected,
            selectedDeviceBeforeFilter = defaultDeviceName,
            validateAccessKey = validateAccessKey
        )
    }

    /**
     * Cheap, offline format check for the Porcupine key BEFORE the network-backed
     * engine build: a real key is a long, non-placeholder token. This lets us fail
     * fast on obviously-bad keys and keep the expensive [WakeWordDetector.validateAccessKey]
     * for keys that at least look plausible.
     */
    private fun looksLikeValidKeyFormat(key: String?): Boolean {
        val trimmed = key?.trim().orEmpty()
        if (trimmed.length < MIN_ACCESS_KEY_LENGTH) return false
        val lower = trimmed.lowercase()
        if (KEY_PLACEHOLDER_FRAGMENTS.any { lower.contains(it) }) return false
        if (trimmed.startsWith("<") && trimmed.endsWith(">")) return false
        if (trimmed.toSet().size <= 1) return false // all-same-char (e.g. "xxxx…")
        return true
    }

    /** Log only the SHAPE of the key — presence, length, and a SHA-256 prefix. Never the key. */
    private fun logAccessKeyShape(key: String?, present: Boolean) {
        val length = key?.length ?: 0
        val hashPrefix = key?.takeIf { it.isNotBlank() }?.let { accessKeyHashPrefix(it) } ?: "none"
        logger.info(
            "Porcupine access key shape: accessKeyPresent={}, accessKeyLength={}, accessKeyHashPrefix={}",
            present, length, hashPrefix
        )
    }

    private fun accessKeyHashPrefix(key: String): String = try {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        digest.joinToString("") { "%02x".format(it) }.take(8)
    } catch (e: Exception) {
        "unknown"
    }

    /** Front [selectedName] within [accepted] so it is attempted first; else keep classifier order. */
    private fun orderForSelection(
        accepted: List<WakeWordInputDevice>,
        selectedName: String?
    ): List<WakeWordInputDevice> {
        if (selectedName.isNullOrBlank()) return accepted
        val idx = accepted.indexOfFirst { it.name == selectedName }
        if (idx <= 0) return accepted
        return listOf(accepted[idx]) + accepted.filterIndexed { i, _ -> i != idx }
    }

    /** The device to try first: the dropdown choice, else the last-persisted working mic. */
    private fun selectedWakeDeviceName(): String? {
        wakeInputDeviceChoice?.takeIf { it.isNotBlank() }?.let { return it }
        return readPersistedWorkingDevice()
    }

    private fun readPersistedWorkingDevice(): String? = try {
        Preferences.userNodeForPackage(VoiceTab::class.java).get("wakeInputDevice", null)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /**
     * Repopulate the wake-word microphone dropdown ("Auto" + accepted mics) off the
     * FX thread, preselecting the persisted choice when it is still present.
     */
    private fun refreshWakeInputDeviceChoices() {
        Thread {
            val accepted = try {
                WakeWordInputDevices.listWithClassification().accepted.map { it.name }
            } catch (e: Exception) {
                logger.debug("Could not enumerate wake input devices: {}", e.message)
                emptyList()
            }
            val items = listOf(AUTO_DEVICE_LABEL) + accepted
            val persisted = readWakeInputDeviceChoicePref()
            val toSelect = if (persisted != null && items.contains(persisted)) persisted else AUTO_DEVICE_LABEL
            Platform.runLater {
                suppressComboPersist = true
                wakeInputDeviceCombo.items.setAll(items)
                wakeInputDeviceCombo.value = toSelect
                suppressComboPersist = false
                wakeInputDeviceChoice = toSelect.takeIf { it != AUTO_DEVICE_LABEL }
            }
        }.apply {
            name = "WakeDeviceEnum"
            isDaemon = true
            start()
        }
    }

    private fun persistWakeInputDeviceChoice(name: String?) {
        try {
            val prefs = Preferences.userNodeForPackage(VoiceTab::class.java)
            if (name.isNullOrBlank()) prefs.remove("wakeInputDeviceChoice") else prefs.put("wakeInputDeviceChoice", name)
        } catch (e: Exception) {
            logger.debug("Could not persist wake input device choice: {}", e.message)
        }
    }

    private fun readWakeInputDeviceChoicePref(): String? = try {
        Preferences.userNodeForPackage(VoiceTab::class.java)
            .get("wakeInputDeviceChoice", null)
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    private fun buildDetector(
        request: WakeWordAttempt,
        accessKey: String,
        customModel: CustomModelInfo?,
        sensitivity: Float
    ): WakeWordDetector = when (request.mode) {
        WakeWordMode.CUSTOM_RU -> WakeWordDetector(
            accessKey = accessKey,
            keywordPaths = listOfNotNull(customModel?.path),
            sensitivity = sensitivity,
            deviceName = request.device?.name,
            onWakeWordDetected = { keywordIndex -> onWakeWordDetected(keywordIndex) }
        )
        WakeWordMode.BUILTIN_JARVIS -> WakeWordDetector.createWithBuiltInKeywords(
            accessKey = accessKey,
            keywords = listOf(BuiltInKeyword.JARVIS),
            sensitivity = sensitivity,
            deviceName = request.device?.name,
            onWakeWordDetected = { keywordIndex -> onWakeWordDetected(keywordIndex) }
        )
    }

    private fun buildCustomModelInfo(): CustomModelInfo? {
        val path = resolveWakeWordModelPath() ?: return null
        val file = File(path)
        val exists = file.exists()
        return CustomModelInfo(
            path = path,
            exists = exists,
            sizeBytes = if (exists) file.length() else 0L,
            readable = exists && file.canRead()
        )
    }

    private fun persistWakeInputDevice(device: WakeWordInputDevice) {
        try {
            Preferences.userNodeForPackage(VoiceTab::class.java).put("wakeInputDevice", device.name)
        } catch (e: Exception) {
            logger.debug("Could not persist wake input device '{}': {}", device.name, e.message)
        }
    }

    /**
     * "Test Wake Word Setup": dry-probe custom then built-in on a background
     * thread (so native init never stalls the FX thread), stop any probe handle
     * the initializer opened, then show + log the section-6 diagnostics JSON.
     */
    private fun runWakeWordDiagnostics() {
        Thread {
            val json = try {
                buildWakeWordInitializer().diagnose().testWakeWordSetupJson()
            } catch (e: Exception) {
                logger.error("Wake word diagnostics failed", e)
                "{\"error\":\"diagnostics failed\"}"
            }
            logger.info("Wake word setup diagnostics: {}", json)
            Platform.runLater {
                val area = TextArea(json).apply {
                    isEditable = false
                    isWrapText = true
                    prefRowCount = 12
                    prefColumnCount = 40
                }
                val alert = Alert(Alert.AlertType.INFORMATION)
                alert.title = "Wake Word Setup"
                alert.headerText = "Wake word diagnostics"
                alert.dialogPane.content = area
                alert.showAndWait()
            }
        }.apply {
            name = "WakeWordDiagnostics"
            isDaemon = true
            start()
        }
    }

    private fun stopAlwaysListening() {
        wakeWordDetector?.stop()
        wakeWordDetector = null
        isAlwaysListening = false
        voiceSession.disableAlwaysListening()
        voiceControlService.onAlwaysListeningChanged(false)
        
        Platform.runLater {
            toggleListeningBtn.text = WakeWordOutcomeUi.START_LABEL
            toggleListeningBtn.style = TOGGLE_START_STYLE
        }

        logger.info("🔇 Always listening mode deactivated")
    }

    private fun resolveWakeWordModelPath(): String? {
        val resource = javaClass.getResource("/models/jarvis_ru.ppn") ?: return null

        return try {
            if (resource.protocol == "file") {
                Path.of(resource.toURI()).toString()
            } else {
                val tmpDir = Path.of(System.getenv("JARVIS_TMP_DIR") ?: System.getProperty("java.io.tmpdir"))
                Files.createDirectories(tmpDir)
                val tempFile = Files.createTempFile(tmpDir, "jarvis-ru-", ".ppn")
                javaClass.getResourceAsStream("/models/jarvis_ru.ppn")?.use { input ->
                    Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING)
                } ?: return null
                tempFile.toFile().deleteOnExit()
                tempFile.toString()
            }
        } catch (e: Exception) {
            logger.warn("Failed to resolve bundled wake-word model path", e)
            null
        }
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
            // The detector self-pauses on every wake detection (WakeWordDetector). If the session
            // could not start (usually a dropped voice transport after a cancel/error), we MUST
            // re-arm the detector so the NEXT "Jarvis" is heard again — otherwise the wake word is
            // detected once and then silently ignored forever. Also try to restore the transport so
            // the retry actually succeeds.
            logger.warn("⚠️ Could not start voice session (rejected) — re-arming wake word and restoring transport")
            if (!voiceWebSocketClient.isConnected) {
                logger.info("🔌 Voice transport not ready — reconnecting")
                try {
                    voiceWebSocketClient.connect()
                } catch (e: Exception) {
                    logger.warn("Reconnect attempt failed: {}", e.message)
                }
            }
            if (isAlwaysListening) {
                wakeWordDetector?.resume()
                logger.info("🎧 Wake word detection re-armed after rejected start")
            }
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

    companion object {
        // Pinned to the ai.picovoice:porcupine-java version in this module's pom.
        private const val PORCUPINE_RUNTIME_VERSION = "4.0.0"
        private const val TOGGLE_START_STYLE =
            "-fx-font-size: 14px; -fx-background-color: #4A90E2; -fx-text-fill: white;"
        private const val TOGGLE_STOP_STYLE =
            "-fx-font-size: 14px; -fx-background-color: #E74C3C; -fx-text-fill: white;"
        // Sentinel dropdown item meaning "let the initializer pick the best real mic".
        private const val AUTO_DEVICE_LABEL = "Auto"
        // Picovoice access keys are ~56-char base64 tokens; anything much shorter is bogus.
        private const val MIN_ACCESS_KEY_LENGTH = 40
        private val KEY_PLACEHOLDER_FRAGMENTS = listOf("your-key", "changeme", "placeholder", "xxxx")
    }
}
