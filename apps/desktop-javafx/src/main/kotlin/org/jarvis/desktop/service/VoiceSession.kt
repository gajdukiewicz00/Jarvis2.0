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
 * States: IDLE, LISTENING_WAKE_WORD (ready), LISTENING (recording), PROCESSING,
 * TTS_PLAYBACK (speaking), COOLDOWN, ERROR.
 *
 * Robustness guarantees (so always-listening never leaks after many commands):
 * - Every terminal path funnels through [recoverToWakeListening], which cancels all timers,
 *   stops recording, clears correlationId, resumes media, and re-arms the wake word.
 * - Every active state has a bound: LISTENING (listen timeout), PROCESSING (processing
 *   timeout), TTS_PLAYBACK (speaking timeout), plus a WATCHDOG on an independent thread that
 *   force-recovers any non-idle state that overstays [VoiceConfig.maxActiveStateMs].
 * - Every transition is logged with full diagnostics.
 *
 * Thread-safe: all state transitions are atomic.
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

    /** Active (non-idle) states have a maximum dwell time; used by the watchdog. */
    private fun maxDwellMsFor(s: VoiceState): Long = when (s) {
        VoiceState.LISTENING -> VoiceConfig.listenTimeoutMs + 5000
        VoiceState.PROCESSING -> VoiceConfig.processingTimeoutMs + 5000
        VoiceState.TTS_PLAYBACK -> VoiceConfig.maxSpeakingMs + 5000
        VoiceState.COOLDOWN -> VoiceConfig.cooldownMs + 5000
        else -> Long.MAX_VALUE  // IDLE / LISTENING_WAKE_WORD / ERROR: no bound
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
                        "🐶 voice.session.watchdog_recovered: state={} stuck for {}ms (> {}ms), correlationId={}, diag=[{}]",
                        s, dwell, bound, correlationId, safeDiagnostics()
                    )
                    recoverToWakeListening("watchdog_recovered:$s")
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
            "🔀 voice.session.transition {} -> {} | reason='{}' correlationId={} alwaysListening={} mediaPaused={} transportReady={} diag=[{}]",
            prev, newState, reason, corr, alwaysListeningEnabled, mediaPausedBySession, safeTransportReady(), safeDiagnostics()
        )
        onStateChange(newState, corr)
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
                "🔀 voice.session.transition {} -> LISTENING | reason='wake/manual' correlationId={} manual={} diag=[{}]",
                current, newCorrelationId, isManualTalk, safeDiagnostics()
            )
            onStateChange(VoiceState.LISTENING, newCorrelationId)

            if (isManualTalk) {
                onStartRecording()
                startListenTimeout()
            } else {
                recordingStartFuture = scheduler.schedule({
                    if (currentState.get() == VoiceState.LISTENING) {
                        onStartRecording()
                        startListenTimeout()
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
            recoverToWakeListening("empty_or_noise_transcript")
            return
        }

        // Transition to PROCESSING
        if (currentState.compareAndSet(VoiceState.LISTENING, VoiceState.PROCESSING)) {
            stateEnteredAtMs = System.currentTimeMillis()
            onStopRecording()
            correlationId?.let { onSendEndOfSpeech(it) }
            logger.info("🔀 voice.session.transition LISTENING -> PROCESSING | transcript='{}' correlationId={}", transcript, correlationId)
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
            onStopRecording()
        }

        enterState(VoiceState.TTS_PLAYBACK, correlationId, "tts_audio_received")

        // SPEAKING watchdog: a hung audio device (line.drain blocking) must not freeze us here.
        ttsPlaybackTimeoutFuture?.cancel(false)
        ttsPlaybackTimeoutFuture = scheduler.schedule({
            if (currentState.get() == VoiceState.TTS_PLAYBACK) {
                logger.warn("⏰ Speaking timeout after {} ms, recovering, correlationId={}", VoiceConfig.maxSpeakingMs, correlationId)
                recoverToWakeListening("speaking_timeout")
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
            recoverToWakeListening("text_only_response")
        }
    }

    private fun endCooldown() {
        if (currentState.get() != VoiceState.COOLDOWN) {
            logger.debug("Not in COOLDOWN state, skipping cooldown end")
            return
        }
        recoverToWakeListening("cooldown_finished")
    }

    /**
     * THE single recovery chokepoint. Cancels every timer, stops recording, clears the
     * correlationId + flags, resumes media, and re-arms the wake word — then transitions to
     * LISTENING_WAKE_WORD so the next wake starts a fresh session. Idempotent-ish and safe to
     * call from any thread (main scheduler, watchdog, or callbacks).
     */
    @Synchronized
    private fun recoverToWakeListening(reason: String) {
        val prev = currentState.get()
        if (prev == VoiceState.IDLE) {
            // Disabled — do not re-arm.
            return
        }
        val endedCorrelationId = correlationId

        // Cancel every timer.
        recordingStartFuture?.cancel(false); recordingStartFuture = null
        listenTimeoutFuture?.cancel(false); listenTimeoutFuture = null
        processingTimeoutFuture?.cancel(false); processingTimeoutFuture = null
        ttsPlaybackTimeoutFuture?.cancel(false); ttsPlaybackTimeoutFuture = null
        cooldownFuture?.cancel(false); cooldownFuture = null

        // Stop recording if it was in progress.
        if (prev == VoiceState.LISTENING) {
            try { onStopRecording() } catch (e: Exception) { logger.debug("stopRecording during recovery: {}", e.message) }
        }

        correlationId = null

        // Resume media if we paused it.
        if (mediaPausedBySession) {
            try { onResumeMedia() } catch (e: Exception) { logger.debug("resumeMedia during recovery: {}", e.message) }
            mediaPausedBySession = false
        }

        enterState(VoiceState.LISTENING_WAKE_WORD, null, "recover:$reason (from $prev, corr=$endedCorrelationId)")
        try { onEnableWakeWord() } catch (e: Exception) { logger.debug("enableWakeWord during recovery: {}", e.message) }
    }

    /**
     * Cancel the current session due to error or timeout, surfacing the error, then recover.
     */
    fun cancelSession(reason: String, error: Exception? = null) {
        logger.warn("❌ Cancelling session: reason='{}', state={}, correlationId={}", reason, currentState.get(), correlationId, error)
        try { onSessionError(reason, error) } catch (e: Exception) { logger.debug("onSessionError: {}", e.message) }
        recoverToWakeListening("cancel:$reason")
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
        if (currentState.get() == VoiceState.LISTENING) {
            try { onStopRecording() } catch (e: Exception) { logger.debug("stopRecording on disable: {}", e.message) }
        }
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
                onStopRecording()
                correlationId?.let { onSendEndOfSpeech(it) }
                try { onSpeakTimeout() } catch (e: Exception) { logger.debug("Could not speak timeout message: {}", e.message) }
                // Recover after a brief delay so the timeout phrase can play.
                scheduler.schedule({ recoverToWakeListening("listen_timeout") }, 2000, TimeUnit.MILLISECONDS)
            }
        }, VoiceConfig.listenTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun startProcessingTimeout() {
        processingTimeoutFuture = scheduler.schedule({
            if (currentState.get() == VoiceState.PROCESSING) {
                logger.warn("⏰ Processing timeout after {} ms, correlationId={}", VoiceConfig.processingTimeoutMs, correlationId)
                recoverToWakeListening("processing_timeout")
            }
        }, VoiceConfig.processingTimeoutMs, TimeUnit.MILLISECONDS)
    }

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
