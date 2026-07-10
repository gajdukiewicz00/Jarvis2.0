package org.jarvis.desktop.service

import org.jarvis.desktop.config.VoiceConfig
import org.jarvis.desktop.model.VoiceState
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Voice session state machine that coordinates the entire voice command flow.
 *
 * Concrete states (the [VoiceState] enum) map to the conceptual lifecycle states like so:
 *   LISTENING_WAKE_WORD = WAKE_LISTENING (armed, waiting for "Jarvis")
 *   LISTENING + !recordingActive = WAKE_DETECTED (wake fired, recording not yet started)
 *   LISTENING + recordingActive  = RECORDING_COMMAND (mic streaming the command)
 *   PROCESSING = PROCESSING_COMMAND (waiting for server response/action)
 *   TTS_PLAYBACK = SPEAKING (playing the TTS answer)
 *   COOLDOWN = short guard pause after speaking, then back to WAKE_LISTENING
 *   ERROR = CANCELLED/ERROR (never dwelt in — recovers almost immediately)
 *
 * Robustness guarantees (so always-listening never leaks after many commands):
 * - Every terminal path funnels through the single idempotent [completeCommandSession], which
 *   cancels all timers, stops recording, clears the correlationId + flags, resumes media, and
 *   re-arms the wake word. A second completion for the same correlationId is a safe no-op.
 * - Every active state has a BACKSTOP bound enforced by an independent WATCHDOG thread that
 *   force-recovers a state that overstays its safe dwell time ([maxDwellMsFor]):
 *     WAKE_DETECTED 3s, RECORDING 15s, PROCESSING 20s, SPEAKING 20s, ERROR 2s.
 * - Every transition is logged with a full [debugSnapshot].
 *
 * Thread-safe: state transitions are atomic and recovery is synchronized on the instance.
 */
class VoiceSession(
    private val onStateChange: (VoiceState, String?) -> Unit,  // (state, correlationId)
    private val onStartRecording: () -> Unit,
    private val onStopRecording: () -> Unit,
    private val onSendEndOfSpeech: (String) -> Unit,  // correlationId
    private val onEnableWakeWord: () -> Unit,
    private val onDisableWakeWord: () -> Unit,
    private val onPauseMedia: () -> Unit,  // Pause media playback on wake word
    private val onResumeMedia: () -> Unit,  // Resume media playback after command
    private val onSpeakTimeout: () -> Unit,  // Speak "Sir, I couldn't hear you"
    private val onSessionError: (String, Exception?) -> Unit,
    private val voiceTransportReady: () -> Boolean = { true },  // Check if voice WS is connected
    private val sessionDiagnostics: () -> String = { "" }  // recorder/sending/ws/wake/tts flags for logs
) {
    private val logger = LoggerFactory.getLogger(VoiceSession::class.java)

    private val currentState = AtomicReference(VoiceState.IDLE)
    @Volatile private var correlationId: String? = null
    @Volatile private var mediaPausedBySession = false  // Track if we paused media
    @Volatile private var stateEnteredAtMs = System.currentTimeMillis()
    @Volatile private var alwaysListeningEnabled = false

    // --- Lifecycle bookkeeping for the debug snapshot + idempotent completion ---
    @Volatile private var recordingActive = false          // mic actually streaming a command
    @Volatile private var startedCorrelationId: String? = null
    @Volatile private var endedCorrelationId: String? = null
    @Volatile private var lastCompletedCorrelationId: String? = null  // idempotency key
    @Volatile private var lastCommandCompletedAt: Long = 0L
    @Volatile private var lastRecoveryReason: String = "none"

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "VoiceSession-Scheduler").apply { isDaemon = true }
    }
    // Watchdog runs on its OWN thread so it can recover the session even if the main scheduler
    // thread is momentarily busy — it must never be blocked by the work it is meant to rescue.
    private val watchdogScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "VoiceSession-Watchdog").apply { isDaemon = true }
    }

    private var recordingStartFuture: ScheduledFuture<*>? = null
    private var listenTimeoutFuture: ScheduledFuture<*>? = null
    private var processingTimeoutFuture: ScheduledFuture<*>? = null
    private var ttsPlaybackTimeoutFuture: ScheduledFuture<*>? = null
    private var cooldownFuture: ScheduledFuture<*>? = null
    @Volatile private var watchdogFuture: ScheduledFuture<*>? = null

    val state: VoiceState get() = currentState.get()
    val currentCorrelationId: String? get() = correlationId
    val isRecordingActive: Boolean get() = recordingActive
    val alwaysListeningActive: Boolean get() = alwaysListeningEnabled

    /**
     * Exact per-state dwell bound (the safe upper limit before the watchdog force-recovers).
     * WAKE_DETECTED (LISTENING before recording starts) is bounded tighter than the full
     * RECORDING window, so a wake that never begins capturing is caught within 3s.
     */
    private fun maxDwellMsFor(s: VoiceState): Long = when (s) {
        VoiceState.LISTENING ->
            if (recordingActive) VoiceConfig.recordingMaxMs else VoiceConfig.wakeDetectedTimeoutMs
        VoiceState.PROCESSING -> VoiceConfig.processingMaxMs
        VoiceState.TTS_PLAYBACK -> VoiceConfig.maxSpeakingMs
        VoiceState.COOLDOWN -> VoiceConfig.cooldownMs + 3000
        VoiceState.ERROR -> VoiceConfig.errorMaxMs
        else -> Long.MAX_VALUE  // IDLE / LISTENING_WAKE_WORD: no bound
    }

    private fun startWatchdog() {
        if (watchdogFuture != null) return
        watchdogFuture = watchdogScheduler.scheduleWithFixedDelay({
            try {
                val s = currentState.get()
                val dwell = System.currentTimeMillis() - stateEnteredAtMs
                val bound = minOf(maxDwellMsFor(s), VoiceConfig.maxActiveStateMs)
                if (s != VoiceState.IDLE && s != VoiceState.LISTENING_WAKE_WORD && dwell > bound) {
                    logger.warn(
                        "🐶 voice.session.watchdog_recovered: state={} recording={} stuck for {}ms (> {}ms) {}",
                        s, recordingActive, dwell, bound, debugSnapshot()
                    )
                    completeCommandSession(correlationId, "watchdog_recovered:$s")
                }
            } catch (e: Exception) {
                logger.debug("Watchdog tick error: {}", e.message)
            }
        }, VoiceConfig.watchdogIntervalMs, VoiceConfig.watchdogIntervalMs, TimeUnit.MILLISECONDS)
        logger.info("🐶 Voice session watchdog started (interval={}ms)", VoiceConfig.watchdogIntervalMs)
    }

    private fun safeDiagnostics(): String = try { sessionDiagnostics() } catch (e: Exception) { "diag-error" }
    private fun safeTransportReady(): Boolean = try { voiceTransportReady() } catch (e: Exception) { false }

    /** Single logging + onStateChange chokepoint. Records dwell start and the full context. */
    private fun enterState(newState: VoiceState, corr: String?, reason: String) {
        val prev = currentState.getAndSet(newState)
        stateEnteredAtMs = System.currentTimeMillis()
        logger.info(
            "🔀 voice.session.transition {} -> {} | reason='{}' {}",
            prev, newState, reason, debugSnapshot()
        )
        onStateChange(newState, corr)
    }

    /** Begin mic capture for the current command and arm the RECORDING timeout. */
    private fun beginRecording() {
        recordingActive = true
        startedCorrelationId = correlationId
        try { onStartRecording() } catch (e: Exception) { logger.debug("startRecording: {}", e.message) }
        startListenTimeout()
    }

    /** Stop mic capture if active. Idempotent — safe to call from any recovery path. */
    private fun endRecording() {
        if (recordingActive) {
            recordingActive = false
            try { onStopRecording() } catch (e: Exception) { logger.debug("stopRecording: {}", e.message) }
        }
    }

    /**
     * Start a new voice session.
     *
     * @param isManualTalk true for push-to-talk (no delay), false for wake-word (delayed start to skip the wake word audio)
     * Transitions: IDLE/LISTENING_WAKE_WORD → LISTENING
     */
    fun startSession(isManualTalk: Boolean = false): String? {
        val expectedStates = setOf(VoiceState.IDLE, VoiceState.LISTENING_WAKE_WORD)
        val current = currentState.get()

        if (current !in expectedStates) {
            logger.warn("⚠️ Cannot start session in state {}, expected {}", current, expectedStates)
            return null
        }

        if (!safeTransportReady()) {
            logger.warn("⚠️ Cannot start session — voice transport not connected")
            onSessionError("Voice unavailable — not connected", null)
            return null
        }

        val newCorrelationId = UUID.randomUUID().toString()

        // Transition to LISTENING (atomic guard against a concurrent wake)
        if (currentState.compareAndSet(current, VoiceState.LISTENING)) {
            correlationId = newCorrelationId
            recordingActive = false  // WAKE_DETECTED until recording actually starts
            stateEnteredAtMs = System.currentTimeMillis()
            startWatchdog()
            onDisableWakeWord()

            // Pause any media playback to ensure clear audio capture
            try {
                onPauseMedia()
                mediaPausedBySession = true
            } catch (e: Exception) {
                mediaPausedBySession = false
                logger.debug("Could not pause media: {}", e.message)
            }

            logger.info(
                "🔀 voice.session.transition {} -> LISTENING | reason='wake/manual' manual={} {}",
                current, isManualTalk, debugSnapshot()
            )
            onStateChange(VoiceState.LISTENING, newCorrelationId)

            if (isManualTalk) {
                beginRecording()
            } else {
                recordingStartFuture = scheduler.schedule({
                    if (currentState.get() == VoiceState.LISTENING) {
                        beginRecording()
                    }
                }, VoiceConfig.wakeWordDelayMs, TimeUnit.MILLISECONDS)
            }

            return newCorrelationId
        }

        return null
    }

    /**
     * Called when a FINAL transcript is received from the server.
     * Immediately stops recording and transitions to PROCESSING.
     */
    fun onFinalTranscript(transcript: String, msgCorrelationId: String?) {
        val current = currentState.get()

        if (current != VoiceState.LISTENING) {
            logger.debug("Ignoring final transcript in state {}: '{}'", current, transcript)
            return
        }

        // Verify correlation ID matches (if provided) — ignore late/stale transcripts.
        if (msgCorrelationId != null && msgCorrelationId != correlationId) {
            logger.warn("Correlation ID mismatch: expected={}, received={}", correlationId, msgCorrelationId)
            return
        }

        recordingStartFuture?.cancel(false)
        recordingStartFuture = null
        listenTimeoutFuture?.cancel(false)
        listenTimeoutFuture = null

        // Empty/noise transcript → close the STT session and recover silently, do not hang.
        if (transcript.isBlank() || shouldIgnoreAsNoise(transcript)) {
            logger.info("🔇 Ignoring transcript as empty/noise: '{}', correlationId={}", transcript, correlationId)
            correlationId?.let { onSendEndOfSpeech(it) }
            completeCommandSession(correlationId, "empty_or_noise_transcript")
            return
        }

        // Transition to PROCESSING
        if (currentState.compareAndSet(VoiceState.LISTENING, VoiceState.PROCESSING)) {
            stateEnteredAtMs = System.currentTimeMillis()
            endRecording()
            correlationId?.let { onSendEndOfSpeech(it) }
            logger.info("🔀 voice.session.transition LISTENING -> PROCESSING | transcript='{}' {}", transcript, debugSnapshot())
            onStateChange(VoiceState.PROCESSING, correlationId)
            startProcessingTimeout()
        }
    }

    /**
     * Called when TTS audio playback starts.
     * Transitions: PROCESSING → TTS_PLAYBACK
     */
    fun onTtsPlaybackStarted() {
        val current = currentState.get()

        // Allow transition from PROCESSING or even LISTENING (if response came quickly).
        // Ignore late audio after DONE (state back at LISTENING_WAKE_WORD/IDLE).
        if (current !in setOf(VoiceState.PROCESSING, VoiceState.LISTENING)) {
            logger.debug("Ignoring TTS playback start in state {} (late/stale audio)", current)
            return
        }

        listenTimeoutFuture?.cancel(false)
        processingTimeoutFuture?.cancel(false)

        if (current == VoiceState.LISTENING) {
            endRecording()
        }

        enterState(VoiceState.TTS_PLAYBACK, correlationId, "tts_audio_received")

        // SPEAKING watchdog: a hung audio device (line.drain blocking) must not freeze us here.
        ttsPlaybackTimeoutFuture?.cancel(false)
        ttsPlaybackTimeoutFuture = scheduler.schedule({
            if (currentState.get() == VoiceState.TTS_PLAYBACK) {
                logger.warn("⏰ Speaking timeout after {} ms, recovering, correlationId={}", VoiceConfig.maxSpeakingMs, correlationId)
                completeCommandSession(correlationId, "speaking_timeout")
            }
        }, VoiceConfig.maxSpeakingMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Called when TTS audio playback finishes (AudioPlayer guarantees this fires even on
     * NO_OUTPUT_DEVICE / PLAYBACK_FAILED via its finally block).
     * Transitions: TTS_PLAYBACK → COOLDOWN
     */
    fun onTtsPlaybackFinished() {
        val current = currentState.get()

        if (current != VoiceState.TTS_PLAYBACK) {
            logger.debug("Ignoring TTS playback finish in state {}", current)
            return
        }

        ttsPlaybackTimeoutFuture?.cancel(false)
        ttsPlaybackTimeoutFuture = null

        enterState(VoiceState.COOLDOWN, correlationId, "tts_playback_finished")

        cooldownFuture = scheduler.schedule({ endCooldown() }, VoiceConfig.cooldownMs, TimeUnit.MILLISECONDS)
    }

    /**
     * A server RESPONSE frame arrived. If TTS audio doesn't follow within the grace window,
     * treat it as text-only and recover instead of waiting out the full processing timeout.
     */
    fun onResponseReceived() {
        if (currentState.get() != VoiceState.PROCESSING) return
        scheduler.schedule({
            if (currentState.get() == VoiceState.PROCESSING) {
                onTextOnlyResponse()
            }
        }, VoiceConfig.textOnlyGraceMs, TimeUnit.MILLISECONDS)
    }

    /**
     * A text-only response (no TTS audio) completed — recover immediately instead of waiting
     * for the processing timeout. Safe no-op if we already left PROCESSING.
     */
    fun onTextOnlyResponse() {
        if (currentState.get() == VoiceState.PROCESSING) {
            logger.info("💬 Text-only response, recovering without waiting for playback, correlationId={}", correlationId)
            completeCommandSession(correlationId, "text_only_response")
        }
    }

    private fun endCooldown() {
        if (currentState.get() != VoiceState.COOLDOWN) {
            logger.debug("Not in COOLDOWN state, skipping cooldown end")
            return
        }
        completeCommandSession(correlationId, "cooldown_finished")
    }

    /**
     * THE single idempotent completion chokepoint. Every command path (success, timeout, noise,
     * cancel, watchdog) ends here. Cancels every timer, stops recording, clears the correlationId
     * + flags, resumes media, and re-arms the wake word — then transitions to LISTENING_WAKE_WORD
     * so the next wake starts a fresh session.
     *
     * Idempotent: a second call for the same [correlationId] while already settled is ignored, so
     * a `finally`-block completion plus an explicit completion never double-recover.
     */
    @Synchronized
    fun completeCommandSession(correlationId: String?, reason: String) {
        val settled = currentState.get() in setOf(VoiceState.LISTENING_WAKE_WORD, VoiceState.IDLE)
        if (correlationId != null && correlationId == lastCompletedCorrelationId && settled) {
            logger.debug("voice.session.complete ignored (already completed correlationId={}, reason={})", correlationId, reason)
            return
        }
        lastCompletedCorrelationId = correlationId ?: lastCompletedCorrelationId
        endedCorrelationId = correlationId ?: this.correlationId
        lastCommandCompletedAt = System.currentTimeMillis()
        lastRecoveryReason = reason
        recoverToWakeListening(reason)
    }

    /**
     * Low-level recovery mechanism. Cancels timers, stops recording, clears state, resumes media,
     * and re-arms the wake word. Called only via [completeCommandSession] (idempotency gate) or
     * [disableAlwaysListening]. Safe from any thread.
     */
    @Synchronized
    private fun recoverToWakeListening(reason: String) {
        val prev = currentState.get()
        if (prev == VoiceState.IDLE) {
            // Disabled — do not re-arm.
            return
        }

        // Cancel every timer.
        recordingStartFuture?.cancel(false); recordingStartFuture = null
        listenTimeoutFuture?.cancel(false); listenTimeoutFuture = null
        processingTimeoutFuture?.cancel(false); processingTimeoutFuture = null
        ttsPlaybackTimeoutFuture?.cancel(false); ttsPlaybackTimeoutFuture = null
        cooldownFuture?.cancel(false); cooldownFuture = null

        // Stop recording (idempotent — closes the per-command mic line so it is never leaked).
        endRecording()

        correlationId = null

        // Resume media if we paused it.
        if (mediaPausedBySession) {
            try { onResumeMedia() } catch (e: Exception) { logger.debug("resumeMedia during recovery: {}", e.message) }
            mediaPausedBySession = false
        }

        enterState(VoiceState.LISTENING_WAKE_WORD, null, "recover:$reason (from $prev)")
        try { onEnableWakeWord() } catch (e: Exception) { logger.debug("enableWakeWord during recovery: {}", e.message) }
    }

    /**
     * Cancel the current session due to error or timeout, surfacing the error, then recover
     * through the idempotent completion chokepoint.
     */
    fun cancelSession(reason: String, error: Exception? = null) {
        val corr = correlationId
        logger.warn("❌ Cancelling session: reason='{}', state={}, correlationId={}", reason, currentState.get(), corr, error)
        try { onSessionError(reason, error) } catch (e: Exception) { logger.debug("onSessionError: {}", e.message) }
        completeCommandSession(corr, "cancel:$reason")
    }

    /**
     * Set initial state to LISTENING_WAKE_WORD when always-listening mode is enabled.
     */
    fun enableAlwaysListening() {
        alwaysListeningEnabled = true
        startWatchdog()
        val current = currentState.get()
        if (current == VoiceState.IDLE) {
            enterState(VoiceState.LISTENING_WAKE_WORD, null, "enable_always_listening")
        }
    }

    /**
     * Return to IDLE state when always-listening mode is disabled (user explicitly stopped).
     */
    fun disableAlwaysListening() {
        logger.info("🔇 Disabling always-listening mode")
        alwaysListeningEnabled = false

        // Cancel every timer and stop recording, WITHOUT re-arming the wake word.
        recordingStartFuture?.cancel(false); recordingStartFuture = null
        listenTimeoutFuture?.cancel(false); listenTimeoutFuture = null
        processingTimeoutFuture?.cancel(false); processingTimeoutFuture = null
        ttsPlaybackTimeoutFuture?.cancel(false); ttsPlaybackTimeoutFuture = null
        cooldownFuture?.cancel(false); cooldownFuture = null
        endRecording()
        correlationId = null
        if (mediaPausedBySession) {
            try { onResumeMedia() } catch (e: Exception) { logger.debug("resumeMedia on disable: {}", e.message) }
            mediaPausedBySession = false
        }

        enterState(VoiceState.IDLE, null, "disable_always_listening")
        onDisableWakeWord()
    }

    /**
     * Check if transcript should be ignored as background noise.
     */
    private fun shouldIgnoreAsNoise(transcript: String): Boolean {
        if (!VoiceConfig.noiseFilteringEnabled) return false

        val trimmed = transcript.trim().lowercase()

        if (trimmed.length < VoiceConfig.minTranscriptLength) {
            logger.debug("Transcript too short ({} chars): '{}'", trimmed.length, trimmed)
            return true
        }

        if (trimmed in VoiceConfig.noiseWords) {
            logger.debug("Transcript is known noise word: '{}'", trimmed)
            return true
        }

        val words = trimmed.split("\\s+".toRegex())
        if (words.size == 1 && words[0].length <= 3) {
            logger.debug("Single short word, likely noise: '{}'", trimmed)
            return true
        }

        return false
    }

    private fun startListenTimeout() {
        listenTimeoutFuture = scheduler.schedule({
            if (currentState.get() == VoiceState.LISTENING) {
                logger.warn("⏰ Listen timeout after {} ms, correlationId={}", VoiceConfig.listenTimeoutMs, correlationId)
                endRecording()
                correlationId?.let { onSendEndOfSpeech(it) }
                try { onSpeakTimeout() } catch (e: Exception) { logger.debug("Could not speak timeout message: {}", e.message) }
                // Recover after a brief delay so the timeout phrase can play.
                val corr = correlationId
                scheduler.schedule({ completeCommandSession(corr, "listen_timeout") }, 2000, TimeUnit.MILLISECONDS)
            }
        }, VoiceConfig.listenTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun startProcessingTimeout() {
        processingTimeoutFuture = scheduler.schedule({
            if (currentState.get() == VoiceState.PROCESSING) {
                logger.warn("⏰ Processing timeout after {} ms, correlationId={}", VoiceConfig.processingTimeoutMs, correlationId)
                completeCommandSession(correlationId, "processing_timeout")
            }
        }, VoiceConfig.processingTimeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Human-readable list of still-armed timers, for the debug snapshot. */
    private fun pendingTimers(): String {
        val timers = mutableListOf<String>()
        if (recordingStartFuture?.isDone == false) timers.add("recordingStart")
        if (listenTimeoutFuture?.isDone == false) timers.add("listen")
        if (processingTimeoutFuture?.isDone == false) timers.add("processing")
        if (ttsPlaybackTimeoutFuture?.isDone == false) timers.add("tts")
        if (cooldownFuture?.isDone == false) timers.add("cooldown")
        return if (timers.isEmpty()) "none" else timers.joinToString("|")
    }

    /**
     * Compact one-line JSON snapshot of the entire voice lifecycle, for logs and Diagnostics.
     * External flags (wakeWordActive, isSendingAllowed, webSocketConnected) ride in via the
     * caller-supplied [sessionDiagnostics] string under "diag".
     */
    fun debugSnapshot(): String {
        val s = currentState.get()
        val dwell = System.currentTimeMillis() - stateEnteredAtMs
        return buildString {
            append("{")
            append("\"voiceState\":\"").append(s).append("\",")
            append("\"alwaysListeningActive\":").append(alwaysListeningEnabled).append(",")
            append("\"recorderActive\":").append(recordingActive).append(",")
            append("\"ttsPlaybackActive\":").append(s == VoiceState.TTS_PLAYBACK).append(",")
            append("\"currentCorrelationId\":").append(jsonStr(correlationId)).append(",")
            append("\"startedCorrelationId\":").append(jsonStr(startedCorrelationId)).append(",")
            append("\"endedCorrelationId\":").append(jsonStr(endedCorrelationId)).append(",")
            append("\"lastCommandCompletedAt\":").append(lastCommandCompletedAt).append(",")
            append("\"lastRecoveryReason\":").append(jsonStr(lastRecoveryReason)).append(",")
            append("\"pendingTimers\":").append(jsonStr(pendingTimers())).append(",")
            append("\"stateDwellMs\":").append(dwell).append(",")
            append("\"diag\":").append(jsonStr(safeDiagnostics()))
            append("}")
        }
    }

    private fun jsonStr(v: String?): String =
        if (v == null) "null" else "\"" + v.replace("\\", "\\\\").replace("\"", "'") + "\""

    /**
     * Cleanup resources.
     */
    fun shutdown() {
        logger.info("Shutting down VoiceSession")
        recordingStartFuture?.cancel(true)
        listenTimeoutFuture?.cancel(true)
        processingTimeoutFuture?.cancel(true)
        ttsPlaybackTimeoutFuture?.cancel(true)
        cooldownFuture?.cancel(true)
        watchdogFuture?.cancel(true)
        scheduler.shutdownNow()
        watchdogScheduler.shutdownNow()
    }
}
