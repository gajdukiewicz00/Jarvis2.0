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
 * Ensures proper state transitions and cleanup:
 * - IDLE → LISTENING (on wake word)
 * - LISTENING → PROCESSING (on final transcript)
 * - PROCESSING → TTS_PLAYBACK (on TTS audio received)
 * - TTS_PLAYBACK → COOLDOWN (on TTS finished)
 * - COOLDOWN → IDLE (after cooldown timer)
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
    private val onSessionError: (String, Exception?) -> Unit
) {
    private val logger = LoggerFactory.getLogger(VoiceSession::class.java)
    
    private val currentState = AtomicReference(VoiceState.IDLE)
    private var correlationId: String? = null
    private var mediaPausedBySession = false  // Track if we paused media
    
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "VoiceSession-Scheduler").apply { isDaemon = true }
    }
    
    private var recordingStartFuture: ScheduledFuture<*>? = null
    private var listenTimeoutFuture: ScheduledFuture<*>? = null
    private var processingTimeoutFuture: ScheduledFuture<*>? = null
    private var cooldownFuture: ScheduledFuture<*>? = null
    
    val state: VoiceState get() = currentState.get()
    val currentCorrelationId: String? get() = correlationId
    
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
        
        val newCorrelationId = UUID.randomUUID().toString()
        correlationId = newCorrelationId
        
        logger.info("🎤 Starting voice session: correlationId={}, {} → LISTENING, manual={}", newCorrelationId, current, isManualTalk)
        
        // Transition to LISTENING
        if (currentState.compareAndSet(current, VoiceState.LISTENING)) {
            onDisableWakeWord()
            
            // Pause any media playback to ensure clear audio capture
            try {
                onPauseMedia()
                mediaPausedBySession = true
                logger.info("⏸️ Media paused for voice command, correlationId={}", newCorrelationId)
            } catch (e: Exception) {
                mediaPausedBySession = false
                logger.debug("Could not pause media: {}", e.message)
            }
            
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
        
        // Verify correlation ID matches (if provided)
        if (msgCorrelationId != null && msgCorrelationId != correlationId) {
            logger.warn("Correlation ID mismatch: expected={}, received={}", correlationId, msgCorrelationId)
            return
        }
        
        logger.info("📝 Final transcript received: '{}', correlationId={}", transcript, correlationId)
        
        recordingStartFuture?.cancel(false)
        recordingStartFuture = null
        listenTimeoutFuture?.cancel(false)
        listenTimeoutFuture = null
        
        // Check for noise/filler - if enabled and transcript is just noise, end session silently
        if (shouldIgnoreAsNoise(transcript)) {
            logger.info("🔇 Ignoring transcript as noise: '{}', correlationId={}", transcript, correlationId)
            endSessionSilently("Noise detected")
            return
        }
        
        // Transition to PROCESSING
        if (currentState.compareAndSet(VoiceState.LISTENING, VoiceState.PROCESSING)) {
            logger.info("⏹️ Stopping recording, transitioning to PROCESSING, correlationId={}", correlationId)
            
            // IMMEDIATELY stop recording
            onStopRecording()
            
            // Send end-of-speech marker
            correlationId?.let { onSendEndOfSpeech(it) }
            
            onStateChange(VoiceState.PROCESSING, correlationId)
            
            // Start processing timeout
            startProcessingTimeout()
        }
    }
    
    /**
     * Called when TTS audio playback starts.
     * Transitions: PROCESSING → TTS_PLAYBACK
     */
    fun onTtsPlaybackStarted() {
        val current = currentState.get()
        
        // Allow transition from PROCESSING or even LISTENING (if response came quickly)
        if (current !in setOf(VoiceState.PROCESSING, VoiceState.LISTENING)) {
            logger.debug("Ignoring TTS playback start in state {}", current)
            return
        }
        
        logger.info("🔊 TTS playback started, correlationId={}", correlationId)
        
        // Cancel any pending timeouts
        listenTimeoutFuture?.cancel(false)
        processingTimeoutFuture?.cancel(false)
        
        // Ensure recording is stopped
        if (current == VoiceState.LISTENING) {
            onStopRecording()
        }
        
        currentState.set(VoiceState.TTS_PLAYBACK)
        onStateChange(VoiceState.TTS_PLAYBACK, correlationId)
    }
    
    /**
     * Called when TTS audio playback finishes.
     * Transitions: TTS_PLAYBACK → COOLDOWN
     */
    fun onTtsPlaybackFinished() {
        val current = currentState.get()
        
        if (current != VoiceState.TTS_PLAYBACK) {
            logger.debug("Ignoring TTS playback finish in state {}", current)
            return
        }
        
        logger.info("🔊 TTS playback finished, starting cooldown ({} ms), correlationId={}", 
            VoiceConfig.cooldownMs, correlationId)
        
        currentState.set(VoiceState.COOLDOWN)
        onStateChange(VoiceState.COOLDOWN, correlationId)
        
        // Start cooldown timer
        cooldownFuture = scheduler.schedule({
            endCooldown()
        }, VoiceConfig.cooldownMs, TimeUnit.MILLISECONDS)
    }
    
    /**
     * End cooldown and return to IDLE/LISTENING_WAKE_WORD state.
     */
    private fun endCooldown() {
        val current = currentState.get()
        
        if (current != VoiceState.COOLDOWN) {
            logger.debug("Not in COOLDOWN state, skipping cooldown end")
            return
        }
        
        val endedCorrelationId = correlationId
        correlationId = null
        
        logger.info("✅ Cooldown finished, re-enabling wake word, correlationId={}", endedCorrelationId)
        
        // Resume media if we paused it
        if (mediaPausedBySession) {
            try {
                onResumeMedia()
                logger.info("▶️ Media resumed after command, correlationId={}", endedCorrelationId)
            } catch (e: Exception) {
                logger.debug("Could not resume media: {}", e.message)
            }
            mediaPausedBySession = false
        }
        
        // Transition to LISTENING_WAKE_WORD (always-listening mode) or IDLE
        currentState.set(VoiceState.LISTENING_WAKE_WORD)
        onEnableWakeWord()
        onStateChange(VoiceState.LISTENING_WAKE_WORD, null)
    }
    
    /**
     * End session silently without TTS (for noise/timeout cases).
     */
    private fun endSessionSilently(reason: String) {
        val endedCorrelationId = correlationId
        
        logger.info("🔇 Ending session silently: reason='{}', correlationId={}", reason, endedCorrelationId)
        
        // Cancel all timers
        recordingStartFuture?.cancel(false)
        recordingStartFuture = null
        listenTimeoutFuture?.cancel(false)
        processingTimeoutFuture?.cancel(false)
        cooldownFuture?.cancel(false)
        
        // Stop recording if in progress
        if (currentState.get() == VoiceState.LISTENING) {
            onStopRecording()
            endedCorrelationId?.let { onSendEndOfSpeech(it) }
        }
        
        correlationId = null
        
        // Resume media if we paused it
        if (mediaPausedBySession) {
            try {
                onResumeMedia()
                logger.info("▶️ Media resumed after silent end, correlationId={}", endedCorrelationId)
            } catch (e: Exception) {
                logger.debug("Could not resume media: {}", e.message)
            }
            mediaPausedBySession = false
        }
        
        // Return to wake word listening state
        currentState.set(VoiceState.LISTENING_WAKE_WORD)
        onEnableWakeWord()
        onStateChange(VoiceState.LISTENING_WAKE_WORD, null)
    }
    
    /**
     * Cancel the current session due to error or timeout.
     */
    fun cancelSession(reason: String, error: Exception? = null) {
        val current = currentState.get()
        val endedCorrelationId = correlationId
        
        logger.warn("❌ Cancelling session: reason='{}', state={}, correlationId={}", 
            reason, current, endedCorrelationId, error)
        
        // Cancel all timers
        recordingStartFuture?.cancel(false)
        recordingStartFuture = null
        listenTimeoutFuture?.cancel(false)
        processingTimeoutFuture?.cancel(false)
        cooldownFuture?.cancel(false)
        
        // Stop recording if in progress
        if (current == VoiceState.LISTENING) {
            onStopRecording()
        }
        
        correlationId = null
        
        // Resume media if we paused it
        if (mediaPausedBySession) {
            try {
                onResumeMedia()
                logger.info("▶️ Media resumed after session cancel, correlationId={}", endedCorrelationId)
            } catch (e: Exception) {
                logger.debug("Could not resume media: {}", e.message)
            }
            mediaPausedBySession = false
        }
        
        // Notify about error
        onSessionError(reason, error)
        
        // Return to wake word listening state
        currentState.set(VoiceState.LISTENING_WAKE_WORD)
        onEnableWakeWord()
        onStateChange(VoiceState.LISTENING_WAKE_WORD, null)
    }
    
    /**
     * Set initial state to LISTENING_WAKE_WORD when always-listening mode is enabled.
     */
    fun enableAlwaysListening() {
        val current = currentState.get()
        if (current == VoiceState.IDLE) {
            logger.info("🎧 Enabling always-listening mode")
            currentState.set(VoiceState.LISTENING_WAKE_WORD)
            onStateChange(VoiceState.LISTENING_WAKE_WORD, null)
        }
    }
    
    /**
     * Return to IDLE state when always-listening mode is disabled.
     */
    fun disableAlwaysListening() {
        logger.info("🔇 Disabling always-listening mode")
        
        // Cancel any active session
        if (currentState.get() != VoiceState.IDLE) {
            cancelSession("Always-listening disabled")
        }
        
        currentState.set(VoiceState.IDLE)
        onStateChange(VoiceState.IDLE, null)
    }
    
    /**
     * Check if transcript should be ignored as background noise.
     */
    private fun shouldIgnoreAsNoise(transcript: String): Boolean {
        if (!VoiceConfig.noiseFilteringEnabled) return false
        
        val trimmed = transcript.trim().lowercase()
        
        // Too short
        if (trimmed.length < VoiceConfig.minTranscriptLength) {
            logger.debug("Transcript too short ({} chars): '{}'", trimmed.length, trimmed)
            return true
        }
        
        // Known noise/filler word
        if (trimmed in VoiceConfig.noiseWords) {
            logger.debug("Transcript is known noise word: '{}'", trimmed)
            return true
        }
        
        // Single word that's not a known command - could extend this
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
                logger.warn("⏰ Listen timeout after {} ms, correlationId={}", 
                    VoiceConfig.listenTimeoutMs, correlationId)
                
                // Stop recording
                onStopRecording()
                correlationId?.let { onSendEndOfSpeech(it) }
                
                // Speak timeout message: "Sir, I couldn't hear you"
                try {
                    onSpeakTimeout()
                } catch (e: Exception) {
                    logger.debug("Could not speak timeout message: {}", e.message)
                }
                
                // Return to listening state after brief delay (wait for TTS to finish)
                scheduler.schedule({
                    val endedCorrelationId = correlationId
                    correlationId = null
                    logger.info("🔇 Listen timeout handled, returning to wake word listening, correlationId={}", endedCorrelationId)
                    
                    // Resume media if we paused it
                    if (mediaPausedBySession) {
                        try {
                            onResumeMedia()
                            logger.info("▶️ Media resumed after timeout, correlationId={}", endedCorrelationId)
                        } catch (e: Exception) {
                            logger.debug("Could not resume media: {}", e.message)
                        }
                        mediaPausedBySession = false
                    }
                    
                    currentState.set(VoiceState.LISTENING_WAKE_WORD)
                    onEnableWakeWord()
                    onStateChange(VoiceState.LISTENING_WAKE_WORD, null)
                }, 2000, TimeUnit.MILLISECONDS) // Wait for TTS to finish
            }
        }, VoiceConfig.listenTimeoutMs, TimeUnit.MILLISECONDS)
    }
    
    private fun startProcessingTimeout() {
        processingTimeoutFuture = scheduler.schedule({
            if (currentState.get() == VoiceState.PROCESSING) {
                logger.warn("⏰ Processing timeout after {} ms, correlationId={}", 
                    VoiceConfig.processingTimeoutMs, correlationId)
                endSessionSilently("Processing timeout")
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
        cooldownFuture?.cancel(true)
        scheduler.shutdownNow()
    }
}

