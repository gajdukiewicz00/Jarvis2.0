package org.jarvis.agent.voice

import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.agent.killswitch.KillSwitchManager
import org.jarvis.commands.agent.AgentEvent
import org.jarvis.commands.voice.VoiceFeedback
import org.slf4j.LoggerFactory

/**
 * Phase 7 — bridges wake-word detection to the voice-gateway voice loop.
 *
 * <p>Pass 1 design: the JavaFX shell (or any audio capture frontend) calls
 * {@link #onWakeWord} when the wake word fires. This class:</p>
 * <ol>
 *   <li>Refuses if the kill switch is engaged (no mic capture in that state).</li>
 *   <li>POSTs {@code /api/v1/voice/sessions} via {@link VoiceLoopClient}.</li>
 *   <li>Hands the new session id to the supplied transcript pump
 *       (typically Whisper STT in the JavaFX shell).</li>
 *   <li>Posts the resulting transcript via
 *       {@code /api/v1/voice/sessions/{id}/utterance}.</li>
 *   <li>Hands the spoken {@link VoiceFeedback#getSpokenText()} to the TTS
 *       playback callback.</li>
 *   <li>Closes the session.</li>
 * </ol>
 *
 * <p>The bridge does NOT do STT or TTS itself; it composes existing
 * components so Pass 2 can swap real audio I/O without changing the
 * voice-loop wire format.</p>
 */
class WakeWordToVoiceBridge(
    private val client: VoiceLoopClient,
    private val killSwitch: KillSwitchManager,
    private val feed: AgentLiveFeed,
    private val agentId: String,
    private val transcribe: () -> String?,
    private val speak: (VoiceFeedback) -> Unit
) {
    private val log = LoggerFactory.getLogger(WakeWordToVoiceBridge::class.java)

    /**
     * Invoke from the wake-word callback. Runs synchronously on the
     * caller thread; for production use Phase 6 dispatches each call to
     * a worker pool to avoid blocking audio capture.
     */
    fun onWakeWord(): VoiceLoopClient.UtteranceReply? {
        if (killSwitch.isEngaged()) {
            log.warn("wake word ignored — kill switch engaged")
            feed.emit(AgentEvent.warn(
                agentId,
                AgentEvent.Type.KILL_SWITCH_ENGAGED,
                "wake word suppressed by kill switch",
                emptyMap()
            ))
            return null
        }

        val session = client.startSession() ?: run {
            log.error("could not start voice session")
            return null
        }

        val transcript = try {
            transcribe()
        } catch (ex: Exception) {
            log.error("STT failed: {}", ex.message, ex)
            null
        }
        if (transcript == null || transcript.isBlank()) {
            log.warn("[{}] empty transcript — closing session without dispatch", session.sessionId)
            client.endSession(session.sessionId)
            return null
        }

        val reply = client.submitUtterance(session.sessionId, transcript)
        if (reply?.feedback != null) {
            try {
                speak(reply.feedback)
            } catch (ex: Exception) {
                log.warn("[{}] TTS playback failed: {}", session.sessionId, ex.message)
            }
        } else {
            log.warn("[{}] no reply feedback — speaking generic failure", session.sessionId)
            speak(VoiceFeedback.builder()
                .code("FAILED")
                .level(VoiceFeedback.Level.ERROR)
                .spokenText("Не удалось обработать команду, сэр.")
                .build())
        }
        client.endSession(session.sessionId)
        return reply
    }
}
