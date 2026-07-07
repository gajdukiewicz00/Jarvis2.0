package org.jarvis.desktop.features.voice

import org.jarvis.desktop.features.status.StatusLevel
import org.jarvis.desktop.model.VoiceRuntimeState

/**
 * Coherent, structured voice-channel status for the Voice view: connection
 * state plus mic/STT/TTS readiness, expressed in the shared [StatusLevel]
 * vocabulary so it never contradicts any other status surface in the shell.
 */
data class VoiceChannelStatus(
    val connection: StatusLevel,
    val mic: StatusLevel,
    val stt: StatusLevel,
    val tts: StatusLevel,
    val headline: String
)

/**
 * Maps [VoiceRuntimeState] to a [VoiceChannelStatus].
 *
 * Deliberately built only from *typed* [VoiceRuntimeState] fields
 * (`connectionPhase`, `sttAvailable`, `ttsAvailable`, device presence) —
 * NEVER from [VoiceRuntimeState.lastError] free text. That discipline is
 * what prevents the "connected but DEGRADED" contradiction: an idle,
 * non-failure signal such as a `NO_AUDIO_RECEIVED` response never touches
 * these typed fields (see
 * `org.jarvis.desktop.service.VoiceWebSocketClient.IDLE_RESPONSE_CODES`),
 * so it can never accidentally downgrade the status shown here — unlike a
 * naive mapping that pattern-matches on error text.
 */
object VoiceChannelStatusMapper {

    fun map(state: VoiceRuntimeState): VoiceChannelStatus {
        val connection = connectionStatus(state.connectionPhase)
        val mic = micStatus(state)
        val stt = if (state.sttAvailable) StatusLevel.UP else StatusLevel.DEGRADED
        val tts = if (state.ttsAvailable) StatusLevel.UP else StatusLevel.DEGRADED
        return VoiceChannelStatus(
            connection = connection,
            mic = mic,
            stt = stt,
            tts = tts,
            headline = headline(connection, mic, stt, tts)
        )
    }

    /** Placeholder status shown before the first [VoiceRuntimeState] snapshot arrives. */
    fun unknown(): VoiceChannelStatus = VoiceChannelStatus(
        connection = StatusLevel.UNKNOWN,
        mic = StatusLevel.UNKNOWN,
        stt = StatusLevel.UNKNOWN,
        tts = StatusLevel.UNKNOWN,
        headline = "Checking voice channel…"
    )

    /**
     * Mirrors the canonical `DesktopRuntimeMonitor.ConnectionState.toStatusLevel()`
     * mapping used elsewhere in the shell: CONNECTING/RECONNECTING are both
     * "not confirmed yet" and map to [StatusLevel.UNKNOWN] rather than a
     * guessed tone, so this row can never disagree with the top bar about
     * the same underlying voice connection.
     */
    private fun connectionStatus(phase: VoiceRuntimeState.ConnectionPhase): StatusLevel = when (phase) {
        VoiceRuntimeState.ConnectionPhase.CONNECTED -> StatusLevel.UP
        VoiceRuntimeState.ConnectionPhase.CONNECTING -> StatusLevel.UNKNOWN
        VoiceRuntimeState.ConnectionPhase.RECONNECTING -> StatusLevel.UNKNOWN
        VoiceRuntimeState.ConnectionPhase.DISCONNECTED -> StatusLevel.DOWN
        VoiceRuntimeState.ConnectionPhase.FAILED -> StatusLevel.DOWN
    }

    private fun micStatus(state: VoiceRuntimeState): StatusLevel {
        val input = state.inputDevice ?: return StatusLevel.UNAVAILABLE
        return if (input.available) StatusLevel.UP else StatusLevel.DEGRADED
    }

    private fun headline(connection: StatusLevel, mic: StatusLevel, stt: StatusLevel, tts: StatusLevel): String {
        if (connection != StatusLevel.UP) {
            return when (connection) {
                StatusLevel.DOWN -> "Voice channel disconnected"
                StatusLevel.UNKNOWN -> "Connecting to voice channel…"
                else -> "Voice channel status unknown"
            }
        }
        val degraded = mutableListOf<String>()
        if (mic != StatusLevel.UP) degraded += "microphone"
        if (stt != StatusLevel.UP) degraded += "speech recognition"
        if (tts != StatusLevel.UP) degraded += "speech output"
        return if (degraded.isEmpty()) {
            "Voice channel connected and ready"
        } else {
            "Voice connected — ${degraded.joinToString(", ")} unavailable"
        }
    }
}
