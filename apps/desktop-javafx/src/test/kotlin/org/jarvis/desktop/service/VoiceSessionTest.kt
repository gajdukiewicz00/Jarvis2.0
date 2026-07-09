package org.jarvis.desktop.service

import org.jarvis.desktop.model.VoiceState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for VoiceSession state machine.
 * 
 * These tests verify:
 * - Correct state transitions
 * - Recording starts/stops at appropriate times
 * - Wake word is enabled/disabled correctly
 * - Cooldown behavior works as expected
 * - Noise filtering works
 */
class VoiceSessionTest {
    
    private lateinit var session: VoiceSession
    private val currentState = AtomicReference<VoiceState>()
    private val recordingStarted = AtomicBoolean(false)
    private val recordingStopped = AtomicBoolean(false)
    private val endOfSpeechSent = AtomicBoolean(false)
    private val wakeWordEnabled = AtomicBoolean(true)
    private val lastError = AtomicReference<String>()
    private var lastCorrelationId: String? = null
    
    @BeforeEach
    fun setup() {
        recordingStarted.set(false)
        recordingStopped.set(false)
        endOfSpeechSent.set(false)
        wakeWordEnabled.set(true)
        lastError.set(null)
        lastCorrelationId = null
        
        session = VoiceSession(
            onStateChange = { state, correlationId ->
                currentState.set(state)
                lastCorrelationId = correlationId
            },
            onStartRecording = {
                recordingStarted.set(true)
            },
            onStopRecording = {
                recordingStopped.set(true)
            },
            onSendEndOfSpeech = { correlationId ->
                endOfSpeechSent.set(true)
            },
            onEnableWakeWord = {
                wakeWordEnabled.set(true)
            },
            onDisableWakeWord = {
                wakeWordEnabled.set(false)
            },
            onPauseMedia = {
                // No-op for tests
            },
            onResumeMedia = {
                // No-op for tests
            },
            onSpeakTimeout = {
                // No-op for tests
            },
            onSessionError = { reason, error ->
                lastError.set(reason)
            }
        )
    }
    
    @Test
    @DisplayName("Initial state is IDLE")
    fun initialStateIsIdle() {
        assertEquals(VoiceState.IDLE, session.state)
    }
    
    @Test
    @DisplayName("startSession transitions from IDLE to LISTENING")
    fun startSessionTransitionsFromIdleToListening() {
        val correlationId = session.startSession()
        
        assertNotNull(correlationId)
        assertEquals(VoiceState.LISTENING, session.state)
        assertFalse(wakeWordEnabled.get(), "Wake word should be disabled when listening")
    }
    
    @Test
    @DisplayName("startSession with LISTENING_WAKE_WORD transitions to LISTENING")
    fun startSessionFromListeningWakeWord() {
        session.enableAlwaysListening()
        assertEquals(VoiceState.LISTENING_WAKE_WORD, session.state)
        
        val correlationId = session.startSession()
        
        assertNotNull(correlationId)
        assertEquals(VoiceState.LISTENING, session.state)
    }
    
    @Test
    @DisplayName("startSession returns null if not in valid state")
    fun startSessionReturnsNullIfNotInValidState() {
        // Start a session first
        session.startSession()
        assertEquals(VoiceState.LISTENING, session.state)
        
        // Try to start another session
        val correlationId = session.startSession()
        
        assertNull(correlationId, "Should not start new session while already listening")
    }
    
    @Test
    @Timeout(5)
    @DisplayName("onFinalTranscript transitions from LISTENING to PROCESSING")
    fun onFinalTranscriptTransitionsToProcessing() {
        // Start session
        val correlationId = session.startSession()
        
        // Wait for recording to start (after wake word delay)
        Thread.sleep(500)
        assertTrue(recordingStarted.get(), "Recording should have started")
        
        // Simulate final transcript
        session.onFinalTranscript("сделай громче", correlationId)
        
        // Recording should be stopped
        assertTrue(recordingStopped.get(), "Recording should be stopped after final transcript")
        assertTrue(endOfSpeechSent.get(), "End of speech should be sent after final transcript")
        assertEquals(VoiceState.PROCESSING, session.state)
    }
    
    @Test
    @DisplayName("onFinalTranscript ignores mismatched correlationId")
    fun onFinalTranscriptIgnoresMismatchedCorrelationId() {
        val correlationId = session.startSession()
        Thread.sleep(500)
        
        // Send transcript with wrong correlation ID
        session.onFinalTranscript("test", "wrong-id")
        
        // Should still be in LISTENING state
        assertEquals(VoiceState.LISTENING, session.state)
        assertFalse(recordingStopped.get())
    }
    
    @Test
    @DisplayName("Noise is filtered out silently")
    fun noiseIsFilteredOutSilently() {
        session.enableAlwaysListening()
        val correlationId = session.startSession()
        Thread.sleep(500)
        
        // Send a noisy transcript (short filler word)
        session.onFinalTranscript("э", correlationId)
        
        // Should return to LISTENING_WAKE_WORD, not PROCESSING
        assertEquals(VoiceState.LISTENING_WAKE_WORD, session.state)
        assertTrue(wakeWordEnabled.get(), "Wake word should be re-enabled after noise")
    }
    
    @Test
    @DisplayName("onTtsPlaybackStarted transitions to TTS_PLAYBACK")
    fun onTtsPlaybackStartedTransitionsToTtsPlayback() {
        val correlationId = session.startSession()
        Thread.sleep(500)
        
        session.onFinalTranscript("сделай громче", correlationId)
        assertEquals(VoiceState.PROCESSING, session.state)
        
        session.onTtsPlaybackStarted()
        
        assertEquals(VoiceState.TTS_PLAYBACK, session.state)
    }
    
    @Test
    @Timeout(10)
    @DisplayName("Full flow: IDLE -> LISTENING -> PROCESSING -> TTS -> COOLDOWN -> LISTENING_WAKE_WORD")
    fun fullFlowTest() {
        // Enable always-listening mode
        session.enableAlwaysListening()
        assertEquals(VoiceState.LISTENING_WAKE_WORD, session.state)
        
        // Start session (wake word detected)
        val correlationId = session.startSession()
        assertNotNull(correlationId)
        assertEquals(VoiceState.LISTENING, session.state)
        assertFalse(wakeWordEnabled.get())
        
        // Wait for recording to start
        Thread.sleep(500)
        assertTrue(recordingStarted.get())
        
        // Final transcript received
        session.onFinalTranscript("сделай громче", correlationId)
        assertEquals(VoiceState.PROCESSING, session.state)
        assertTrue(recordingStopped.get())
        
        // TTS playback started
        session.onTtsPlaybackStarted()
        assertEquals(VoiceState.TTS_PLAYBACK, session.state)
        
        // TTS playback finished → cooldown
        session.onTtsPlaybackFinished()
        assertEquals(VoiceState.COOLDOWN, session.state)
        assertFalse(wakeWordEnabled.get(), "Wake word should be disabled during cooldown")
        
        // Wait for cooldown to finish (default is 1500ms)
        for (i in 0..20) {
            if (session.state == VoiceState.LISTENING_WAKE_WORD) {
                break
            }
            Thread.sleep(200)
        }
        
        assertEquals(VoiceState.LISTENING_WAKE_WORD, session.state)
        assertTrue(wakeWordEnabled.get(), "Wake word should be re-enabled after cooldown")
        assertNull(session.currentCorrelationId, "Correlation ID should be cleared")
    }
    
    @Test
    @DisplayName("cancelSession returns to LISTENING_WAKE_WORD")
    fun cancelSessionReturnsToListeningWakeWord() {
        session.enableAlwaysListening()
        val correlationId = session.startSession()
        Thread.sleep(500)
        
        session.cancelSession("Test cancel")
        
        assertEquals(VoiceState.LISTENING_WAKE_WORD, session.state)
        assertTrue(wakeWordEnabled.get())
        assertNull(session.currentCorrelationId)
        assertEquals("Test cancel", lastError.get())
    }
    
    @Test
    @DisplayName("disableAlwaysListening returns to IDLE")
    fun disableAlwaysListeningReturnsToIdle() {
        session.enableAlwaysListening()
        assertEquals(VoiceState.LISTENING_WAKE_WORD, session.state)
        
        session.disableAlwaysListening()
        
        assertEquals(VoiceState.IDLE, session.state)
    }
    
    @Test
    @DisplayName("VoiceState.isWakeWordDisabled returns correct values")
    fun voiceStateIsWakeWordDisabled() {
        assertTrue(VoiceState.LISTENING.isWakeWordDisabled())
        assertTrue(VoiceState.PROCESSING.isWakeWordDisabled())
        assertTrue(VoiceState.TTS_PLAYBACK.isWakeWordDisabled())
        assertTrue(VoiceState.COOLDOWN.isWakeWordDisabled())
        
        assertFalse(VoiceState.IDLE.isWakeWordDisabled())
        assertFalse(VoiceState.LISTENING_WAKE_WORD.isWakeWordDisabled())
    }
    
    @Test
    @DisplayName("VoiceState.isMicMuted returns correct values")
    fun voiceStateIsMicMuted() {
        assertTrue(VoiceState.TTS_PLAYBACK.isMicMuted())
        assertTrue(VoiceState.COOLDOWN.isMicMuted())
        assertTrue(VoiceState.IDLE.isMicMuted())
        
        assertFalse(VoiceState.LISTENING.isMicMuted())
        assertFalse(VoiceState.LISTENING_WAKE_WORD.isMicMuted())
        assertFalse(VoiceState.PROCESSING.isMicMuted())
    }

    @Test
    @DisplayName("command execution failure returns session to LISTENING_WAKE_WORD")
    fun executionFailureReturnsToWakeListening() {
        session.enableAlwaysListening()
        val correlationId = session.startSession()
        Thread.sleep(500)
        assertEquals(VoiceState.LISTENING, session.state)

        // Simulate an action/processing failure surfaced to the session.
        session.cancelSession("Action failed: PC-control unavailable")

        assertEquals(VoiceState.LISTENING_WAKE_WORD, session.state)
        assertTrue(wakeWordEnabled.get(), "Wake word must be re-armed after a failure")
    }

    @Test
    @DisplayName("wake word after a failure starts a fresh command recording session")
    fun wakeWordAfterFailureStartsNewSession() {
        session.enableAlwaysListening()
        val first = session.startSession()
        Thread.sleep(500)
        session.cancelSession("Cancelled by user")
        assertEquals(VoiceState.LISTENING_WAKE_WORD, session.state)

        // Saying "Jarvis" again must start a brand-new capture session.
        recordingStarted.set(false)
        val second = session.startSession()
        assertNotNull(second, "A new session must start after a cancel/failure")
        assertNotEquals(first, second, "The new session must have a fresh correlationId")
        assertEquals(VoiceState.LISTENING, session.state)
        Thread.sleep(500)
        assertTrue(recordingStarted.get(), "Recording must start for the new session")
    }

    @Test
    @DisplayName("disableAlwaysListening disarms the wake word detector")
    fun disableAlwaysListeningDisarmsWakeWord() {
        session.enableAlwaysListening()
        wakeWordEnabled.set(true)

        session.disableAlwaysListening()

        assertEquals(VoiceState.IDLE, session.state)
        assertFalse(wakeWordEnabled.get(), "Disabling must leave the wake word OFF")
    }

    @Test
    @DisplayName("disable then re-enable does not permanently block the next command")
    fun disableThenReEnableAllowsNextCommand() {
        session.enableAlwaysListening()
        session.disableAlwaysListening()
        assertEquals(VoiceState.IDLE, session.state)

        // Re-enabling and saying the wake word must work again.
        session.enableAlwaysListening()
        assertEquals(VoiceState.LISTENING_WAKE_WORD, session.state)
        val correlationId = session.startSession()
        assertNotNull(correlationId, "Next command must start after re-enabling")
        assertEquals(VoiceState.LISTENING, session.state)
    }

    @Test
    @DisplayName("startSession returns null and reports error when voice transport not ready")
    fun startSessionReturnsNullWhenTransportNotReady() {
        val transportReady = AtomicBoolean(false)
        val sessionWithTransportCheck = VoiceSession(
            onStateChange = { state, correlationId ->
                currentState.set(state)
                lastCorrelationId = correlationId
            },
            onStartRecording = { recordingStarted.set(true) },
            onStopRecording = { recordingStopped.set(true) },
            onSendEndOfSpeech = { endOfSpeechSent.set(true) },
            onEnableWakeWord = { wakeWordEnabled.set(true) },
            onDisableWakeWord = { wakeWordEnabled.set(false) },
            onPauseMedia = {},
            onResumeMedia = {},
            onSpeakTimeout = {},
            onSessionError = { reason, _ -> lastError.set(reason) },
            voiceTransportReady = { transportReady.get() }
        )

        // Transport not ready — should fail
        val correlationId = sessionWithTransportCheck.startSession()
        assertNull(correlationId, "Should not start session when transport is not ready")
        assertEquals(VoiceState.IDLE, sessionWithTransportCheck.state)
        assertNotNull(lastError.get(), "Should report error when transport not ready")
        assertTrue(lastError.get()!!.contains("not connected"), "Error should mention connectivity")
        assertFalse(recordingStarted.get(), "Recording should NOT start")

        // Transport becomes ready — should succeed
        lastError.set(null)
        transportReady.set(true)
        val correlationId2 = sessionWithTransportCheck.startSession()
        assertNotNull(correlationId2, "Should start session when transport is ready")
        assertEquals(VoiceState.LISTENING, sessionWithTransportCheck.state)

        sessionWithTransportCheck.shutdown()
    }
}
