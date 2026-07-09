package org.jarvis.desktop.service

import org.jarvis.desktop.model.VoiceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Targets the remaining UNCOVERED guard/edge branches of [VoiceSession] that the
 * sibling [VoiceSessionTest] (happy-path transitions) does not reach:
 *
 *  - [VoiceSession.onFinalTranscript] ignored when not in LISTENING, and its
 *    `msgCorrelationId == null` short-circuit (which skips the mismatch check).
 *  - The two remaining [VoiceSession.shouldIgnoreAsNoise] arms: a known
 *    noise/filler word, and a lone short (<=3 char) non-command word. The
 *    sibling only covers the "too short" (< minTranscriptLength) arm.
 *  - [VoiceSession.onTtsPlaybackStarted] transitioning straight from LISTENING
 *    (the `current == LISTENING -> onStopRecording()` arm) and its ignore guard
 *    when in a state outside {PROCESSING, LISTENING}.
 *  - [VoiceSession.onTtsPlaybackFinished] ignore guard when not in TTS_PLAYBACK.
 *  - The `onPauseMedia` throwing branch of [VoiceSession.startSession], whose
 *    catch clears `mediaPausedBySession` yet still completes the transition.
 *
 * All paths are synchronous state-machine decisions (push-to-talk avoids the
 * wake-word start delay), so no sleeps or timers are needed. Every session is
 * shut down to stop its daemon scheduler.
 */
class VoiceSessionUncoveredBranchTest {

    private val state = AtomicReference<VoiceState>()
    private val recordingStarted = AtomicBoolean(false)
    private val recordingStopped = AtomicBoolean(false)
    private val endOfSpeechSent = AtomicBoolean(false)
    private val wakeWordEnabled = AtomicBoolean(true)

    private fun newSession(
        onPauseMedia: () -> Unit = {}
    ): VoiceSession = VoiceSession(
        onStateChange = { s, _ -> state.set(s) },
        onStartRecording = { recordingStarted.set(true) },
        onStopRecording = { recordingStopped.set(true) },
        onSendEndOfSpeech = { endOfSpeechSent.set(true) },
        onEnableWakeWord = { wakeWordEnabled.set(true) },
        onDisableWakeWord = { wakeWordEnabled.set(false) },
        onPauseMedia = onPauseMedia,
        onResumeMedia = {},
        onSpeakTimeout = {},
        onSessionError = { _, _ -> }
    )

    @Test
    @DisplayName("onFinalTranscript is ignored when the session is not LISTENING")
    fun finalTranscriptIgnoredWhenNotListening() {
        val session = newSession()
        try {
            // Fresh session is IDLE — the state guard must drop the transcript.
            session.onFinalTranscript("turn the volume up", null)

            assertEquals(VoiceState.IDLE, session.state)
            assertFalse(recordingStopped.get(), "no recording stop for a dropped transcript")
            assertFalse(endOfSpeechSent.get(), "no end-of-speech for a dropped transcript")
        } finally {
            session.shutdown()
        }
    }

    @Test
    @DisplayName("onFinalTranscript with a null correlationId skips the mismatch check and processes")
    fun finalTranscriptWithNullCorrelationIdIsAccepted() {
        val session = newSession()
        try {
            session.startSession(isManualTalk = true)
            assertEquals(VoiceState.LISTENING, session.state)

            // msgCorrelationId == null: the `!= null && mismatch` guard short-circuits,
            // so a real (non-noise, multi-word) transcript advances to PROCESSING.
            session.onFinalTranscript("turn the volume up", null)

            assertEquals(VoiceState.PROCESSING, session.state)
            assertTrue(recordingStopped.get())
            assertTrue(endOfSpeechSent.get())
        } finally {
            session.shutdown()
        }
    }

    @Test
    @DisplayName("a known noise/filler word ends the session silently")
    fun knownNoiseWordEndsSilently() {
        val session = newSession()
        try {
            session.startSession(isManualTalk = true)
            assertEquals(VoiceState.LISTENING, session.state)

            // "ага" is in VoiceConfig.noiseWords and is long enough to pass the
            // minTranscriptLength check, so it hits the noise-word arm specifically.
            session.onFinalTranscript("ага", null)

            assertEquals(VoiceState.LISTENING_WAKE_WORD, session.state)
            assertTrue(wakeWordEnabled.get(), "wake word re-enabled after silent noise end")
        } finally {
            session.shutdown()
        }
    }

    @Test
    @DisplayName("a lone short non-command word is treated as noise")
    fun loneShortWordIsNoise() {
        val session = newSession()
        try {
            session.startSession(isManualTalk = true)
            assertEquals(VoiceState.LISTENING, session.state)

            // "xyz": long enough, not a known noise word, but a single <=3 char token,
            // so it falls into the single-short-word noise arm.
            session.onFinalTranscript("xyz", null)

            assertEquals(VoiceState.LISTENING_WAKE_WORD, session.state)
            assertTrue(wakeWordEnabled.get())
        } finally {
            session.shutdown()
        }
    }

    @Test
    @DisplayName("onTtsPlaybackStarted transitions directly from LISTENING and stops recording")
    fun ttsPlaybackStartedFromListeningStopsRecording() {
        val session = newSession()
        try {
            session.startSession(isManualTalk = true)
            assertEquals(VoiceState.LISTENING, session.state)
            recordingStopped.set(false)

            // Response arrived while still LISTENING: the LISTENING arm must stop
            // recording and move straight to TTS_PLAYBACK.
            session.onTtsPlaybackStarted()

            assertEquals(VoiceState.TTS_PLAYBACK, session.state)
            assertTrue(recordingStopped.get(), "recording must be stopped when TTS starts mid-LISTENING")
        } finally {
            session.shutdown()
        }
    }

    @Test
    @DisplayName("onTtsPlaybackStarted is ignored outside PROCESSING/LISTENING")
    fun ttsPlaybackStartedIgnoredWhenIdle() {
        val session = newSession()
        try {
            session.onTtsPlaybackStarted()
            assertEquals(VoiceState.IDLE, session.state)
        } finally {
            session.shutdown()
        }
    }

    @Test
    @DisplayName("onTtsPlaybackFinished is ignored when not in TTS_PLAYBACK")
    fun ttsPlaybackFinishedIgnoredWhenNotPlaying() {
        val session = newSession()
        try {
            // Never entered TTS_PLAYBACK — the guard must drop the callback.
            session.onTtsPlaybackFinished()
            assertEquals(VoiceState.IDLE, session.state)
        } finally {
            session.shutdown()
        }
    }

    @Test
    @DisplayName("startSession completes the transition even when onPauseMedia throws")
    fun startSessionSurvivesPauseMediaFailure() {
        val session = newSession(onPauseMedia = { throw IllegalStateException("no media backend") })
        try {
            val correlationId = session.startSession(isManualTalk = true)

            // The pause failure is swallowed; the session still reaches LISTENING and
            // recording still starts.
            assertNotNull(correlationId, "session should start despite pause-media failure")
            assertEquals(VoiceState.LISTENING, session.state)
            assertTrue(recordingStarted.get())
            assertFalse(wakeWordEnabled.get(), "wake word disabled while listening")
        } finally {
            session.shutdown()
        }
    }
}
