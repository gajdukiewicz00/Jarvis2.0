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
import org.jarvis.desktop.service.WakeWordInitializer
import org.jarvis.desktop.service.WakeWordInputDevice
import org.jarvis.desktop.service.WakeWordInputDevices
import org.jarvis.desktop.service.WakeWordMode
import org.jarvis.desktop.service.wake.ManualOnlyProvider
import org.jarvis.desktop.service.wake.OkHttpWakeSidecarClient
import org.jarvis.desktop.service.wake.OpenWakeWordProvider
import org.jarvis.desktop.service.wake.PorcupineProvider
import org.jarvis.desktop.service.wake.SelectionResult
import org.jarvis.desktop.service.wake.SidecarDiagnosticsData
import org.jarvis.desktop.service.wake.VoskPhraseSpotterProvider
import org.jarvis.desktop.service.wake.WakeEventGate
import org.jarvis.desktop.service.wake.WakeProviderDiagnostics
import org.jarvis.desktop.service.wake.WakeProviderState
import org.jarvis.desktop.service.wake.WakeSidecarAutostart
import org.jarvis.desktop.service.wake.WakeWordCallback
import org.jarvis.desktop.service.wake.WakeWordConfig
import org.jarvis.desktop.service.wake.WakeWordProvider
import org.jarvis.desktop.service.wake.WakeWordProviderManager
import org.jarvis.desktop.service.wake.WakeWordProviderSelector
import org.jarvis.desktop.service.wake.WakeWordProviderType
import ai.picovoice.porcupine.Porcupine.BuiltInKeyword
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
    private val startWakeBtn = Button("Start Wake Word Provider")
    private val restartWakeBtn = Button("Restart Wake Word Provider")
    private val stopWakeBtn = Button("Stop Wake Word Provider")
    private val refreshDevicesBtn = Button("↻ Refresh devices")
    private val wakeRefreshDevicesBtn = Button("↻ Refresh Wake Mics")
    private val wakeInputDeviceCombo = ComboBox<String>()
    private val wakeProviderCombo = ComboBox<String>()
    private val wakePhraseCombo = ComboBox<String>()
    private val wakeThresholdSpinner = Spinner<Double>()
    private val wakeStatusLabel = Label("")
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

    // ── provider-based wake-word runtime state ────────────────────────────────
    // The provider that actually started (a real detector, a sidecar, or manual).
    @Volatile private var activeWakeProvider: WakeWordProvider? = null
    // The Porcupine wrapper (if built this round) — bridges its detector's keyword
    // index into a normalized WakeEvent via emitWake.
    @Volatile private var porcupineProvider: PorcupineProvider? = null
    // Lifecycle safety valve between a wake callback and starting a session.
    @Volatile private var wakeGate: WakeEventGate? = null
    // Last selection outcome, reused by the section-8 diagnostics assembly.
    @Volatile private var lastSelection: SelectionResult? = null
    // The single lifecycle owner for the active wake provider. The JavaFX layer talks
    // to THIS (start/pause/resume/stop), never to a raw provider, so "one active
    // instance" + "pause/resume without teardown" live in one place. Built per start.
    @Volatile private var wakeManager: WakeWordProviderManager? = null
    // The live Porcupine detector captured by the attempt seam — adopted as the
    // pause/resume handle ONLY when the Porcupine provider actually won.
    @Volatile private var pendingPorcupineDetector: WakeWordDetector? = null
    // Guards against re-firing the toggle while an async selector.select() is running.
    @Volatile private var wakeSelectionInProgress = false

    // User's explicit wake-word microphone choice from the dropdown (null == "Auto").
    // A volatile shadow so the background selector can read it off the FX thread
    // without touching a JavaFX property.
    @Volatile private var wakeInputDeviceChoice: String? = null
    // Provider + phrase choices, shadowed so currentWakeConfig() reads them off-thread.
    @Volatile private var wakeProviderChoice: WakeWordProviderType? = null
    @Volatile private var wakePhraseModel: String? = null
    // Detection score threshold from the Threshold control (0.0–1.0), shadowed so the
    // background selector can read it off the FX thread without touching a JavaFX property.
    @Volatile private var wakeThreshold: Double? = null
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
                // Wake-listening just re-armed (session recovered to LISTENING_WAKE_WORD, or
                // always-listening was enabled). Arm the gate's post-command cooldown so the
                // TTS tail / echo cannot immediately re-trigger a wake (the gate is the
                // "suspenders"). Then RESUME the active provider via the manager (the "belt"):
                // for a sidecar provider this POSTs /resume, for Porcupine it resumes the local
                // detector. markCompleted first so any duplicate/stale wake during the
                // 1.5–3s cooldown is still dropped even though detection is live again.
                wakeGate?.markCompleted(System.currentTimeMillis())
                if (isAlwaysListening) {
                    wakeWordDetector?.resume()
                    wakeManager?.resume()
                    logger.info("🎧 Wake word detection re-armed (gate cooldown started, provider resumed)")
                }
            },
            onDisableWakeWord = {
                // A command just started (wake detected) / always-listening disabled. PAUSE the
                // active provider through the manager so it stops streaming wake events while the
                // command records and TTS plays — sidecar providers POST /pause, Porcupine pauses
                // its detector (idempotent with the direct detector pause below).
                wakeWordDetector?.pause()
                wakeManager?.pause()
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

        val toggleRow = HBox(10.0)
        toggleRow.children.add(toggleListeningBtn)
        content.children.add(toggleRow)

        // Wake provider status line (Section-9 message). NON-blocking — never a modal.
        wakeStatusLabel.style = WAKE_STATUS_INFO_STYLE
        wakeStatusLabel.isWrapText = true
        content.children.add(wakeStatusLabel)

        // Wake-word provider selector: Auto / openWakeWord / Vosk / Porcupine / Manual only.
        buildWakeProviderCombo()
        val providerRow = HBox(8.0)
        val providerLabel = Label("Wake Word Provider:")
        providerLabel.style = "-fx-font-size: 11px; -fx-text-fill: #666;"
        providerRow.children.addAll(providerLabel, wakeProviderCombo)
        content.children.add(providerRow)

        // Wake phrase (model) selector: hey jarvis / jarvis (+ a disabled "later" note).
        buildWakePhraseCombo()
        val phraseRow = HBox(8.0)
        val phraseLabel = Label("Wake Phrase:")
        phraseLabel.style = "-fx-font-size: 11px; -fx-text-fill: #666;"
        phraseRow.children.addAll(phraseLabel, wakePhraseCombo)
        content.children.add(phraseRow)

        // Wake-word microphone selector. "Auto" lets the provider pick the best real
        // mic; a specific device pins wake-word capture to it. Populated from the
        // sidecar's authoritative GET /devices when reachable, else local Java Sound.
        wakeInputDeviceCombo.promptText = AUTO_DEVICE_LABEL
        wakeInputDeviceCombo.style = "-fx-font-size: 11px;"
        wakeInputDeviceCombo.prefWidth = 260.0
        wakeInputDeviceCombo.setOnAction {
            if (suppressComboPersist) return@setOnAction
            val value = wakeInputDeviceCombo.value
            wakeInputDeviceChoice = value?.takeIf { it.isNotBlank() && it != AUTO_DEVICE_LABEL }
            persistWakeInputDeviceChoice(wakeInputDeviceChoice)
        }
        // "Refresh Wake Mics": re-fetch the sidecar GET /devices off-thread and repopulate
        // the Microphone combo (falls back to the local Java Sound classify when the sidecar
        // is unreachable). Non-blocking, no hardware/modal.
        wakeRefreshDevicesBtn.style = "-fx-font-size: 11px;"
        wakeRefreshDevicesBtn.setOnAction { refreshWakeInputDeviceChoices() }
        val wakeDeviceRow = HBox(8.0)
        val wakeDeviceLabel = Label("Wake Word Microphone:")
        wakeDeviceLabel.style = "-fx-font-size: 11px; -fx-text-fill: #666;"
        wakeDeviceRow.children.addAll(wakeDeviceLabel, wakeInputDeviceCombo, wakeRefreshDevicesBtn)
        content.children.add(wakeDeviceRow)
        refreshWakeInputDeviceChoices()

        // Wake detection threshold (0.0–1.0). Seeded from the persisted choice, else
        // JARVIS_WAKEWORD_THRESHOLD, else the documented default; feeds WakeWordConfig.threshold.
        buildWakeThresholdSpinner()
        val thresholdRow = HBox(8.0)
        val thresholdLabel = Label("Wake Threshold:")
        thresholdLabel.style = "-fx-font-size: 11px; -fx-text-fill: #666;"
        thresholdRow.children.addAll(thresholdLabel, wakeThresholdSpinner)
        content.children.add(thresholdRow)

        // Wake-word provider controls: diagnostics, explicit start, restart, stop. All non-blocking.
        testWakeWordBtn.style = "-fx-font-size: 12px;"
        testWakeWordBtn.setOnAction { runWakeWordDiagnostics() }
        startWakeBtn.style = "-fx-font-size: 12px;"
        startWakeBtn.setOnAction { startWakeProviderExplicit() }
        restartWakeBtn.style = "-fx-font-size: 12px;"
        restartWakeBtn.setOnAction { restartWakeProvider() }
        stopWakeBtn.style = "-fx-font-size: 12px;"
        stopWakeBtn.setOnAction { stopAlwaysListening() }
        val wakeButtonsRow = HBox(10.0)
        wakeButtonsRow.children.addAll(testWakeWordBtn, startWakeBtn, restartWakeBtn, stopWakeBtn)
        content.children.add(wakeButtonsRow)

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
        if (wakeSelectionInProgress) return
        if (isAlwaysListening) {
            stopAlwaysListening()
        } else {
            startAlwaysListening()
        }
    }

    /**
     * Provider-based Always-Listening start. Builds a single [WakeWordProviderManager]
     * (over a fresh [WakeWordProviderSelector]) and runs [WakeWordProviderManager.start]
     * OFF the FX thread (it does sidecar health/HTTP + autostart, which can block a few
     * seconds), then applies the outcome on the FX thread. The manager owns the single
     * active provider and is the pause/resume/stop handle the session lifecycle drives.
     * The selector ALWAYS returns a started provider (ManualOnlyProvider is the last
     * resort), so we can never leave Always-Listening half-enabled. Manual Talk / WS /
     * STT are untouched.
     */
    private fun startAlwaysListening() {
        wakeSelectionInProgress = true
        Platform.runLater {
            toggleListeningBtn.isDisable = true
            wakeStatusLabel.text = "Selecting wake word provider…"
            wakeStatusLabel.style = WAKE_STATUS_INFO_STYLE
        }
        Thread {
            val config = currentWakeConfig()
            pendingPorcupineDetector = null
            val providers = buildProviders(config)
            porcupineProvider = providers[WakeWordProviderType.PORCUPINE] as? PorcupineProvider
            // isBusy: any state other than the idle-armed LISTENING_WAKE_WORD means a
            // command is in flight, so a wake must be ignored (recording/TTS/cooldown).
            val gate = WakeEventGate(isBusy = { voiceSession.state != VoiceState.LISTENING_WAKE_WORD })
            val callback = buildWakeCallback(gate, { System.currentTimeMillis() }) {
                Platform.runLater { onWakeAccepted() }
            }
            val selector = WakeWordProviderSelector(config, providers)
            val manager = WakeWordProviderManager(config, selector)
            val selection = try {
                // The manager wraps the callback in a generation guard (a late wake from a
                // provider that has since been replaced/stopped is dropped) and stores the
                // winning provider as the single active one.
                manager.start(callback)
            } catch (e: Exception) {
                // SAFE STATE: treat as the manual-only outcome — never a hard failure.
                logger.error("Wake provider selection threw; falling back to Manual Talk only", e)
                null
            }
            Platform.runLater {
                wakeSelectionInProgress = false
                if (selection == null) applyManualOnlySafeState() else applySelectionOutcome(selection, gate, manager)
            }
        }.apply {
            name = "WakeProviderSelect"
            isDaemon = true
            start()
        }
    }

    /**
     * Build the four providers against the resolved [config]. The sidecar-backed
     * providers share one [OkHttpWakeSidecarClient]; Porcupine reuses the existing
     * battle-tested initializer/detector tree and is key-gated by [isPorcupineKeyValid].
     * Never mutates instance state — callers adopt the porcupine wrapper explicitly.
     */
    private fun buildProviders(config: WakeWordConfig): Map<WakeWordProviderType, WakeWordProvider> {
        val http = OkHttpWakeSidecarClient(config.sidecarUrl)
        val autostart = WakeSidecarAutostart.of()
        val porcupine = PorcupineProvider(
            keyValid = { isPorcupineKeyValid() },
            buildInitializer = { buildWakeWordInitializer() },
            onWake = { /* legacy index sink — re-arm handled in onWakeAccepted */ }
        )
        return linkedMapOf(
            WakeWordProviderType.OPENWAKEWORD to OpenWakeWordProvider(http, autostart = autostart),
            WakeWordProviderType.VOSK_PHRASE_SPOTTER to VoskPhraseSpotterProvider(http, autostart = autostart),
            WakeWordProviderType.PORCUPINE to porcupine,
            WakeWordProviderType.MANUAL_ONLY to ManualOnlyProvider()
        )
    }

    /** Resolve the Porcupine key and validate it WITHOUT opening a microphone. */
    private fun isPorcupineKeyValid(): Boolean = try {
        val key = PorcupineAccessKeyResolver.resolve()
        if (key.isNullOrBlank()) false
        else WakeWordDetector.validateAccessKey(key).status == AccessKeyValidation.VALID
    } catch (e: Exception) {
        logger.debug("Porcupine key validation failed: {}", e.message)
        false
    }

    /**
     * Apply a started selection on the FX thread. A real detector provider flips the
     * session into always-listening; the manual-only last resort does NOT (wake is
     * off but Manual Talk keeps working). The Section-9 message is shown as INFO on
     * the status line — never a blocking error while a provider is active.
     */
    private fun applySelectionOutcome(selection: SelectionResult, gate: WakeEventGate, manager: WakeWordProviderManager) {
        lastSelection = selection
        wakeGate = gate
        wakeManager = manager
        activeWakeProvider = selection.selected
        // Only adopt the Porcupine detector's pause/resume handle when Porcupine won;
        // sidecar providers have no local detector (gate handles busy-suppression).
        wakeWordDetector =
            if (selection.selectedType == WakeWordProviderType.PORCUPINE) pendingPorcupineDetector else null
        logger.info(
            "Wake provider selected: type={}, status={}, chain={}",
            selection.selectedType, selection.status, selection.fallbackChain
        )

        val ui = uiOutcomeFor(selection)
        applyWakeUi(ui)
        if (ui.enableWakeSession) {
            voiceSession.enableAlwaysListening()
            voiceControlService.onAlwaysListeningChanged(true)
        } else {
            // Manual-only: wake word unavailable, but Manual Talk still works.
            voiceControlService.onAlwaysListeningChanged(false)
            recordWakeInfo(ui.statusMessage)
        }
    }

    /**
     * SAFE STATE when select() itself throws: no live detector, always-listening OFF,
     * Manual Talk stays usable, a NON-blocking info is surfaced. No global "voice
     * degraded" flag — only wake word is unavailable.
     */
    private fun applyManualOnlySafeState() {
        lastSelection = null
        activeWakeProvider = null
        wakeWordDetector = null
        wakeGate = null
        wakeManager = null
        val ui = manualOnlyOutcome()
        applyWakeUi(ui)
        voiceControlService.onAlwaysListeningChanged(false)
        recordWakeInfo(ui.statusMessage)
    }

    /** Apply the outcome→UI mapping to the toggle button + status line (FX thread). */
    private fun applyWakeUi(ui: WakeUiOutcome) {
        isAlwaysListening = ui.isAlwaysListening
        toggleListeningBtn.text = ui.buttonLabel
        toggleListeningBtn.style = if (ui.isAlwaysListening) TOGGLE_STOP_STYLE else TOGGLE_START_STYLE
        toggleListeningBtn.isDisable = false
        wakeStatusLabel.text = ui.statusMessage
        wakeStatusLabel.style = WAKE_STATUS_INFO_STYLE
        logger.info("Wake provider outcome: {}", ui.statusMessage)
    }

    /** Non-blocking INFO surface for the manual-only / fallback wording. */
    private fun recordWakeInfo(message: String) {
        runtimeMonitor.recordEvent(
            source = DesktopRuntimeMonitor.EventSource.VOICE,
            severity = DesktopRuntimeMonitor.EventSeverity.INFO,
            title = "Wake word",
            details = message
        )
    }

    /** "Restart Wake Word Provider": stop the active provider, then re-run selection. */
    private fun restartWakeProvider() {
        stopAlwaysListening()
        startAlwaysListening()
    }

    /**
     * "Start Wake Word Provider": explicitly (re)start the provider. When nothing is
     * running this is the same as toggling Always-Listening on; when a provider is already
     * active it does a clean stop→start (restart) so the manager is never left with a
     * leaked previous provider. No-op while a selection is already in flight.
     */
    private fun startWakeProviderExplicit() {
        if (wakeSelectionInProgress) return
        if (isAlwaysListening) restartWakeProvider() else startAlwaysListening()
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
                // Capture the successfully-started detector so applySelectionOutcome can
                // adopt it as the pause/resume handle IF the Porcupine provider wins.
                pendingPorcupineDetector = detector
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
     * Repopulate the wake-word microphone dropdown ("Auto" + real input devices) off
     * the FX thread, preselecting the persisted choice when it is still present.
     *
     * The SIDECAR's GET /devices is authoritative (real PortAudio names like "C4K"/
     * "T1"; it does NOT surface the bogus "alsa_playback.java" Java Sound shows). We
     * fall back to the local Java Sound classification only when the sidecar is
     * unreachable, else just "Auto".
     */
    private fun refreshWakeInputDeviceChoices() {
        Thread {
            val fromSidecar = try {
                val url = System.getenv("JARVIS_WAKEWORD_URL")?.takeIf { it.isNotBlank() } ?: DEFAULT_SIDECAR_URL
                OkHttpWakeSidecarClient(url).devices()
            } catch (e: Exception) {
                logger.debug("Could not fetch sidecar wake devices: {}", e.message)
                emptyList()
            }
            val names = if (fromSidecar.isNotEmpty()) fromSidecar else localAcceptedDeviceNames()
            val items = listOf(AUTO_DEVICE_LABEL) + names
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

    private fun localAcceptedDeviceNames(): List<String> = try {
        WakeWordInputDevices.listWithClassification().accepted.map { it.name }
    } catch (e: Exception) {
        logger.debug("Could not enumerate wake input devices: {}", e.message)
        emptyList()
    }

    // ── provider + phrase dropdowns ───────────────────────────────────────────

    /** Wake provider combo: seed from persisted choice, else JARVIS_WAKE_PROVIDER env. */
    private fun buildWakeProviderCombo() {
        wakeProviderCombo.items.setAll(PROVIDER_LABELS)
        wakeProviderCombo.style = "-fx-font-size: 11px;"
        wakeProviderCombo.prefWidth = 200.0
        val initialType = readWakeProviderChoicePref() ?: parseProviderType(System.getenv("JARVIS_WAKE_PROVIDER"))
        wakeProviderCombo.value = labelForProviderType(initialType)
        wakeProviderChoice = initialType
        wakeProviderCombo.setOnAction {
            val type = providerTypeForLabel(wakeProviderCombo.value) ?: WakeWordProviderType.AUTO
            wakeProviderChoice = type
            persistWakeProviderChoice(type)
        }
    }

    /** Wake phrase combo: hey jarvis / jarvis, plus a disabled "custom (later)" note. */
    private fun buildWakePhraseCombo() {
        wakePhraseCombo.items.setAll(PHRASE_LABELS)
        wakePhraseCombo.style = "-fx-font-size: 11px;"
        wakePhraseCombo.prefWidth = 220.0
        // Disable the "custom (later)" item so it can be shown but not chosen.
        wakePhraseCombo.setCellFactory {
            object : ListCell<String>() {
                override fun updateItem(item: String?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = item
                    isDisable = item == PHRASE_CUSTOM_LABEL
                    if (item == PHRASE_CUSTOM_LABEL) style = "-fx-text-fill: #999;"
                }
            }
        }
        val initialModel = readWakePhraseChoicePref()
            ?: System.getenv("JARVIS_WAKEWORD_MODEL")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MODEL
        wakePhraseCombo.value = labelForPhraseModel(initialModel)
        wakePhraseModel = modelForPhraseLabel(wakePhraseCombo.value)
        wakePhraseCombo.setOnAction {
            val label = wakePhraseCombo.value
            if (label == PHRASE_CUSTOM_LABEL) {
                // Not selectable — revert to the last real phrase.
                wakePhraseCombo.value = labelForPhraseModel(wakePhraseModel ?: DEFAULT_MODEL)
                return@setOnAction
            }
            val model = modelForPhraseLabel(label)
            wakePhraseModel = model
            persistWakePhraseChoice(model)
        }
    }

    /**
     * Build the wake-threshold spinner: seed the value from the persisted choice, else
     * JARVIS_WAKEWORD_THRESHOLD, else the documented default. The value is shadowed into
     * [wakeThreshold] (read off-thread by [currentWakeConfig]) and persisted on change.
     */
    private fun buildWakeThresholdSpinner() {
        val initial = readWakeThresholdPref()
            ?: System.getenv("JARVIS_WAKEWORD_THRESHOLD")?.trim()?.toDoubleOrNull()?.coerceIn(0.0, 1.0)
            ?: DEFAULT_THRESHOLD
        wakeThresholdSpinner.valueFactory =
            SpinnerValueFactory.DoubleSpinnerValueFactory(0.0, 1.0, initial, WAKE_THRESHOLD_STEP)
        wakeThresholdSpinner.isEditable = true
        wakeThresholdSpinner.prefWidth = 110.0
        wakeThresholdSpinner.style = "-fx-font-size: 11px;"
        wakeThreshold = initial
        wakeThresholdSpinner.valueProperty().addListener { _, _, newValue ->
            val v = (newValue ?: DEFAULT_THRESHOLD).coerceIn(0.0, 1.0)
            wakeThreshold = v
            persistWakeThreshold(v)
        }
    }

    private fun persistWakeThreshold(value: Double) {
        try {
            Preferences.userNodeForPackage(VoiceTab::class.java).putDouble("wakeThresholdChoice", value)
        } catch (e: Exception) {
            logger.debug("Could not persist wake threshold: {}", e.message)
        }
    }

    private fun readWakeThresholdPref(): Double? = try {
        val prefs = Preferences.userNodeForPackage(VoiceTab::class.java)
        if (prefs.get("wakeThresholdChoice", null) == null) null
        else prefs.getDouble("wakeThresholdChoice", DEFAULT_THRESHOLD).coerceIn(0.0, 1.0)
    } catch (e: Exception) {
        null
    }

    /**
     * Resolve the effective wake config: env supplies url/threshold defaults, while
     * the controls (provider / phrase / microphone / threshold) override
     * type/model/device/threshold. Read entirely off shadowed @Volatile fields so it
     * is safe off the FX thread.
     */
    private fun currentWakeConfig(): WakeWordConfig {
        val base = parseWakeConfig { System.getenv(it) }
        return base.copy(
            type = wakeProviderChoice ?: base.type,
            model = wakePhraseModel ?: base.model,
            threshold = wakeThreshold ?: base.threshold,
            device = selectedWakeDeviceName() ?: base.device
        )
    }

    private fun persistWakeProviderChoice(type: WakeWordProviderType) {
        try {
            Preferences.userNodeForPackage(VoiceTab::class.java).put("wakeProviderChoice", type.name)
        } catch (e: Exception) {
            logger.debug("Could not persist wake provider choice: {}", e.message)
        }
    }

    private fun readWakeProviderChoicePref(): WakeWordProviderType? = try {
        Preferences.userNodeForPackage(VoiceTab::class.java).get("wakeProviderChoice", null)
            ?.let { name -> WakeWordProviderType.entries.firstOrNull { it.name == name } }
    } catch (e: Exception) {
        null
    }

    private fun persistWakePhraseChoice(model: String) {
        try {
            Preferences.userNodeForPackage(VoiceTab::class.java).put("wakePhraseChoice", model)
        } catch (e: Exception) {
            logger.debug("Could not persist wake phrase choice: {}", e.message)
        }
    }

    private fun readWakePhraseChoicePref(): String? = try {
        Preferences.userNodeForPackage(VoiceTab::class.java).get("wakePhraseChoice", null)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
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
     * "Test Wake Word Setup": assemble the Section-8 aggregate diagnostics JSON
     * (selector.providerDiagnostics() + sidecar /diagnostics + /devices) off the FX
     * thread, then show it in a TextArea dialog + log it. NEVER emits the Porcupine key.
     */
    private fun runWakeWordDiagnostics() {
        Thread {
            val json = try {
                val config = currentWakeConfig()
                // Prefer the LIVE manager (reflects the real active provider incl. its paused
                // state + last selection); fall back to a fresh selector when nothing is running.
                val manager = wakeManager
                val providerDiags = manager?.diagnostics()
                    ?: WakeWordProviderSelector(config, buildProviders(config)).providerDiagnostics()
                val selection = manager?.lastSelection() ?: lastSelection
                val sidecarDiag = try {
                    OkHttpWakeSidecarClient(config.sidecarUrl).diagnostics()
                } catch (e: Exception) {
                    logger.debug("Sidecar diagnostics unreachable: {}", e.message)
                    null
                }
                val rejected = try {
                    WakeWordInputDevices.listWithClassification().rejected.map { it.name to it.reason }
                } catch (e: Exception) {
                    emptyList()
                }
                buildDiagnosticsJson(
                    selection = selection,
                    providerDiags = providerDiags,
                    sidecarDiag = sidecarDiag,
                    rejected = rejected,
                    voiceSessionState = voiceSession.state.name,
                    providerPaused = manager?.isPaused() ?: false,
                    recorderActive = voiceSession.isRecordingActive,
                    lastRecoveryReason = voiceSession.lastRecoveryReason
                )
            } catch (e: Exception) {
                logger.error("Wake word diagnostics failed", e)
                "{\"error\":\"diagnostics failed\"}"
            }
            logger.info("Wake word setup diagnostics: {}", json)
            Platform.runLater {
                val area = TextArea(json).apply {
                    isEditable = false
                    isWrapText = true
                    prefRowCount = 16
                    prefColumnCount = 48
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

    /**
     * Stop the active wake provider (SSE + POST /stop for sidecar; detector stop for
     * Porcupine), disable always-listening, reset the toggle. Manual Talk + WS + STT
     * are untouched. Idempotent — safe to call when nothing is running.
     */
    private fun stopAlwaysListening() {
        // Stop through the manager first — it owns the single active provider and clears
        // it (SSE + POST /stop for sidecar; detector stop for Porcupine). Idempotent.
        try {
            wakeManager?.stop()
        } catch (e: Exception) {
            logger.debug("Wake manager stop failed: {}", e.message)
        }
        try {
            activeWakeProvider?.stop()
        } catch (e: Exception) {
            logger.debug("Wake provider stop failed: {}", e.message)
        }
        // Also stop the Porcupine detector directly (idempotent) in case it was
        // adopted as the pause/resume handle.
        wakeWordDetector?.stop()
        wakeManager = null
        activeWakeProvider = null
        porcupineProvider = null
        wakeWordDetector = null
        pendingPorcupineDetector = null
        wakeGate = null
        lastSelection = null
        isAlwaysListening = false
        voiceSession.disableAlwaysListening()
        voiceControlService.onAlwaysListeningChanged(false)

        Platform.runLater {
            toggleListeningBtn.text = START_LABEL
            toggleListeningBtn.style = TOGGLE_START_STYLE
            toggleListeningBtn.isDisable = false
            wakeStatusLabel.text = "Wake word stopped. Manual Talk still works."
            wakeStatusLabel.style = WAKE_STATUS_INFO_STYLE
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
     * The Porcupine detector's keyword-index callback. Bridges the raw index into a
     * normalized [org.jarvis.desktop.service.wake.WakeEvent] via [PorcupineProvider.emitWake],
     * which fires the SAME gate-guarded [WakeWordCallback] the sidecar providers use
     * (emitWake → callback → gate → startSession). If, for any reason, no Porcupine
     * wrapper is set, fall straight through to the session-start path.
     */
    private fun onWakeWordDetected(keywordIndex: Int) {
        logger.info("🎤 Wake word detected via Porcupine! (keyword index: {})", keywordIndex)
        val bridge = porcupineProvider
        if (bridge != null) {
            bridge.emitWake(keywordIndex)
        } else {
            onWakeAccepted()
        }
    }

    /**
     * Runs on the FX thread after a wake event is ACCEPTED by the gate (from any
     * provider). Starts a new voice session and hands the correlationId to the
     * WebSocket transport. STT/router logic is unchanged. On a rejected start, restore
     * the transport and (Porcupine only) re-arm the local detector so the next "Jarvis"
     * is still heard.
     */
    private fun onWakeAccepted() {
        val correlationId = voiceSession.startSession(isManualTalk = false)

        if (correlationId != null) {
            voiceWebSocketClient.startCommand(correlationId)
            logger.info("🎤 Voice session started from wake: correlationId={}", correlationId)
        } else {
            logger.warn("⚠️ Could not start voice session (rejected) — restoring transport / re-arming wake")
            if (!voiceWebSocketClient.isConnected) {
                logger.info("🔌 Voice transport not ready — reconnecting")
                try {
                    voiceWebSocketClient.connect()
                } catch (e: Exception) {
                    logger.warn("Reconnect attempt failed: {}", e.message)
                }
            }
            // Porcupine self-pauses on each hit; sidecar providers have no local detector.
            if (isAlwaysListening) {
                wakeWordDetector?.resume()
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
        // Sentinel dropdown item meaning "let the provider pick the best real mic".
        private const val AUTO_DEVICE_LABEL = "Auto"
        // Picovoice access keys are ~56-char base64 tokens; anything much shorter is bogus.
        private const val MIN_ACCESS_KEY_LENGTH = 40
        private val KEY_PLACEHOLDER_FRAGMENTS = listOf("your-key", "changeme", "placeholder", "xxxx")

        // ── Section-9 button labels (must match renderVoiceStatus literals) ──
        internal const val START_LABEL = "Start Always Listening"
        internal const val STOP_LABEL = "Stop Always Listening"

        // Non-blocking wake status line style (INFO, never an error while a provider is active).
        private const val WAKE_STATUS_INFO_STYLE = "-fx-font-size: 12px; -fx-text-fill: #2C6E9B;"

        // ── wake config defaults (mirror WakeWordConfig defaults) ──
        internal const val DEFAULT_SIDECAR_URL = "http://127.0.0.1:18095"
        internal const val DEFAULT_MODEL = "hey_jarvis"
        internal const val DEFAULT_THRESHOLD = 0.5
        internal const val DEFAULT_DEVICE = "auto"
        // Threshold spinner increment (0.0–1.0 in 0.05 steps).
        private const val WAKE_THRESHOLD_STEP = 0.05

        // ── provider dropdown labels ──
        private const val PROVIDER_AUTO = "Auto"
        private const val PROVIDER_OWW = "openWakeWord"
        private const val PROVIDER_VOSK = "Vosk phrase spotter"
        private const val PROVIDER_PORCUPINE = "Porcupine"
        private const val PROVIDER_MANUAL = "Manual only"
        private val PROVIDER_LABELS = listOf(PROVIDER_AUTO, PROVIDER_OWW, PROVIDER_VOSK, PROVIDER_PORCUPINE, PROVIDER_MANUAL)

        // ── phrase dropdown labels ──
        private const val PHRASE_HEY_JARVIS = "hey jarvis"
        private const val PHRASE_JARVIS = "jarvis"
        private const val PHRASE_CUSTOM_LABEL = "джарвис / custom (coming soon)"
        private val PHRASE_LABELS = listOf(PHRASE_HEY_JARVIS, PHRASE_JARVIS, PHRASE_CUSTOM_LABEL)

        // ── PURE, JavaFX-free decision helpers (unit-tested without a UI) ──

        /** Parse JARVIS_WAKE_PROVIDER into a [WakeWordProviderType] (default AUTO). */
        internal fun parseProviderType(raw: String?): WakeWordProviderType = when (raw?.trim()?.lowercase()) {
            "openwakeword" -> WakeWordProviderType.OPENWAKEWORD
            "vosk", "vosk_phrase_spotter" -> WakeWordProviderType.VOSK_PHRASE_SPOTTER
            "porcupine" -> WakeWordProviderType.PORCUPINE
            "manual", "manual_only" -> WakeWordProviderType.MANUAL_ONLY
            else -> WakeWordProviderType.AUTO
        }

        /** Build the wake config from an env getter, applying documented defaults. */
        internal fun parseWakeConfig(env: (String) -> String?): WakeWordConfig {
            val type = parseProviderType(env("JARVIS_WAKE_PROVIDER"))
            val url = env("JARVIS_WAKEWORD_URL")?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_SIDECAR_URL
            val model = env("JARVIS_WAKEWORD_MODEL")?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MODEL
            val threshold = env("JARVIS_WAKEWORD_THRESHOLD")?.trim()?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: DEFAULT_THRESHOLD
            val device = env("JARVIS_WAKEWORD_DEVICE")?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_DEVICE
            return WakeWordConfig(type, url, model, threshold, device)
        }

        internal fun labelForProviderType(type: WakeWordProviderType): String = when (type) {
            WakeWordProviderType.AUTO -> PROVIDER_AUTO
            WakeWordProviderType.OPENWAKEWORD -> PROVIDER_OWW
            WakeWordProviderType.VOSK_PHRASE_SPOTTER -> PROVIDER_VOSK
            WakeWordProviderType.PORCUPINE -> PROVIDER_PORCUPINE
            WakeWordProviderType.MANUAL_ONLY -> PROVIDER_MANUAL
        }

        internal fun providerTypeForLabel(label: String?): WakeWordProviderType? = when (label) {
            PROVIDER_AUTO -> WakeWordProviderType.AUTO
            PROVIDER_OWW -> WakeWordProviderType.OPENWAKEWORD
            PROVIDER_VOSK -> WakeWordProviderType.VOSK_PHRASE_SPOTTER
            PROVIDER_PORCUPINE -> WakeWordProviderType.PORCUPINE
            PROVIDER_MANUAL -> WakeWordProviderType.MANUAL_ONLY
            else -> null
        }

        internal fun modelForPhraseLabel(label: String?): String = when (label) {
            PHRASE_JARVIS -> "jarvis"
            PHRASE_HEY_JARVIS -> "hey_jarvis"
            else -> DEFAULT_MODEL
        }

        internal fun labelForPhraseModel(model: String): String = when (model.trim().lowercase()) {
            "jarvis" -> PHRASE_JARVIS
            else -> PHRASE_HEY_JARVIS
        }

        /**
         * Map a started [SelectionResult] onto the toggle button + status line. A real
         * detector provider flips into always-listening; MANUAL_ONLY is the "all failed"
         * case (wake off, Manual Talk still works).
         */
        internal fun uiOutcomeFor(selection: SelectionResult): WakeUiOutcome {
            val manualOnly = selection.selectedType == WakeWordProviderType.MANUAL_ONLY
            return WakeUiOutcome(
                buttonLabel = if (manualOnly) START_LABEL else STOP_LABEL,
                isAlwaysListening = !manualOnly,
                enableWakeSession = !manualOnly,
                statusMessage = selection.message,
                manualOnly = manualOnly
            )
        }

        /** The manual-only outcome used when select() itself throws (safe state). */
        internal fun manualOnlyOutcome(): WakeUiOutcome = WakeUiOutcome(
            buttonLabel = START_LABEL,
            isAlwaysListening = false,
            enableWakeSession = false,
            statusMessage = WakeWordProviderSelector.messageFor(WakeWordProviderType.MANUAL_ONLY),
            manualOnly = true
        )

        /** Build the gate-guarded wake callback: an accepted event runs [onAccept]. */
        internal fun buildWakeCallback(
            gate: WakeEventGate,
            now: () -> Long,
            onAccept: () -> Unit
        ): WakeWordCallback = WakeWordCallback { event -> if (gate.offer(event, now())) onAccept() }

        /**
         * Assemble the Section-8 aggregate diagnostics JSON. Dependency-free (kotlinx
         * JSON) and pure. NEVER includes the Porcupine key — only key validity.
         */
        internal fun buildDiagnosticsJson(
            selection: SelectionResult?,
            providerDiags: List<WakeProviderDiagnostics>,
            sidecarDiag: SidecarDiagnosticsData?,
            rejected: List<Pair<String, String>>,
            voiceSessionState: String? = null,
            providerPaused: Boolean? = null,
            recorderActive: Boolean? = null,
            lastRecoveryReason: String? = null
        ): String {
            val oww = providerDiags.firstOrNull { it.providerId == "openwakeword" }
            val vosk = providerDiags.firstOrNull { it.providerId == "vosk" }
            val porcupine = providerDiags.firstOrNull { it.providerId == "porcupine" }
            val porcupineAvailable = porcupine?.installed ?: false
            // The reason a fallback occurred: the first attempt in the chain that did NOT start.
            val fallbackReason = selection?.fallbackChain?.firstOrNull { !it.started }?.reason
            // Sidecar reachability: the openWakeWord provider's own probe result, else a
            // successful /diagnostics fetch implies reachable.
            val sidecarReachable = oww?.reachable ?: (if (sidecarDiag != null) true else null)
            // Models the wake engine reports it has loaded (sidecar authoritative, else provider).
            val modelsLoaded = oww?.models?.takeIf { it.isNotEmpty() } ?: sidecarDiag?.models ?: emptyList()
            val obj = buildJsonObject {
                put("selectedProvider", selection?.selected?.providerId ?: selection?.selectedType?.name?.lowercase() ?: "manual")
                put("providerStatus", (selection?.status ?: WakeProviderState.UNAVAILABLE).name)
                put("providerFallbackReason", fallbackReason)
                put("manualTalkAvailable", true)
                put("sidecarReachable", sidecarReachable)
                putJsonArray("modelsLoaded") { modelsLoaded.forEach { add(it) } }
                put("openWakeWordInstalled", oww?.installed ?: sidecarDiag?.installed)
                put("openWakeWordReachable", oww?.reachable)
                putJsonArray("openWakeWordModels") {
                    (oww?.models?.takeIf { it.isNotEmpty() } ?: sidecarDiag?.models ?: emptyList()).forEach { add(it) }
                }
                put("voskFallbackAvailable", vosk?.installed ?: vosk?.reachable)
                put("porcupineAvailable", porcupineAvailable)
                put("porcupineReason", if (porcupineAvailable) "access key valid" else "access key missing/invalid")
                put("selectedInputDevice", sidecarDiag?.selectedDevice ?: DEFAULT_DEVICE)
                putJsonArray("rejectedDevices") {
                    rejected.forEach { (name, reason) ->
                        addJsonObject {
                            put("name", name)
                            put("reason", reason)
                        }
                    }
                }
                put("lastWakeDetectedAt", sidecarDiag?.lastWakeDetectedAt ?: oww?.lastWakeDetectedAt)
                put("lastWakeScore", sidecarDiag?.lastWakeScore ?: oww?.lastWakeScore)
                // Live voice-session + provider lifecycle state (null when not supplied by a caller
                // that has a session/manager in hand — the pure unit tests pass neither).
                put("voiceSessionState", voiceSessionState)
                put("providerPaused", providerPaused)
                put("recorderActive", recorderActive)
                put("lastError", sidecarDiag?.lastError ?: oww?.lastError)
                put("lastRecoveryReason", lastRecoveryReason)
                putJsonArray("fallbackChain") {
                    selection?.fallbackChain?.forEach { record ->
                        addJsonObject {
                            put("providerId", record.providerId)
                            put("started", record.started)
                            put("reason", record.reason)
                        }
                    }
                }
            }
            return obj.toString()
        }
    }
}

/**
 * The outcome of a wake-provider selection projected onto the Voice tab UI — the
 * pure decision that [VoiceTab] applies to the toggle button + status line. Kept
 * JavaFX-free so it is unit-testable without an FX runtime.
 */
internal data class WakeUiOutcome(
    val buttonLabel: String,
    val isAlwaysListening: Boolean,
    /** Call voiceSession.enableAlwaysListening() only for a real detector provider. */
    val enableWakeSession: Boolean,
    val statusMessage: String,
    val manualOnly: Boolean,
    /** Manual Talk is ALWAYS available regardless of wake-word availability. */
    val manualTalkAvailable: Boolean = true
)
