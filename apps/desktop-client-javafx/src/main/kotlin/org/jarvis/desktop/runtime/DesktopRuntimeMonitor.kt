package org.jarvis.desktop.runtime

import org.jarvis.desktop.model.VoiceRuntimeState
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class DesktopRuntimeMonitor(
    private val clock: Clock = Clock.systemUTC(),
    private val maxEvents: Int = 15
) {

    enum class ConnectionState {
        UNKNOWN,
        CONNECTING,
        CONNECTED,
        DEGRADED,
        DISCONNECTED,
        ERROR
    }

    enum class EventSeverity {
        INFO,
        SUCCESS,
        WARNING,
        ERROR
    }

    enum class EventSource {
        SYSTEM,
        BACKEND,
        VOICE,
        PC_CONTROL,
        ASSISTANT
    }

    data class ConnectionStatus(
        val state: ConnectionState,
        val detail: String,
        val updatedAt: Instant
    )

    data class RuntimeEvent(
        val source: EventSource,
        val severity: EventSeverity,
        val title: String,
        val details: String,
        val timestamp: Instant
    )

    data class Snapshot(
        val backend: ConnectionStatus,
        val voice: ConnectionStatus,
        val pcControl: ConnectionStatus,
        val voiceRuntime: VoiceRuntimeState?,
        val events: List<RuntimeEvent>
    ) {
        fun overallState(): ConnectionState {
            return when {
                backend.state == ConnectionState.ERROR ||
                    voice.state == ConnectionState.ERROR ||
                    pcControl.state == ConnectionState.ERROR -> ConnectionState.ERROR
                backend.state == ConnectionState.DISCONNECTED -> ConnectionState.DISCONNECTED
                backend.state == ConnectionState.DEGRADED ||
                    voice.state == ConnectionState.DEGRADED ||
                    pcControl.state == ConnectionState.DEGRADED -> ConnectionState.DEGRADED
                backend.state == ConnectionState.CONNECTING ||
                    voice.state == ConnectionState.CONNECTING ||
                    pcControl.state == ConnectionState.CONNECTING -> ConnectionState.CONNECTING
                backend.state == ConnectionState.CONNECTED &&
                    voice.state == ConnectionState.CONNECTED &&
                    pcControl.state == ConnectionState.CONNECTED -> ConnectionState.CONNECTED
                else -> ConnectionState.UNKNOWN
            }
        }
    }

    private val lock = ReentrantLock()
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    @Volatile
    private var snapshot = Snapshot(
        backend = initialStatus("Waiting for runtime health check"),
        voice = initialStatus("Voice connection not initialized"),
        pcControl = initialStatus("Desktop action channel not initialized"),
        voiceRuntime = null,
        events = emptyList()
    )

    fun currentSnapshot(): Snapshot = lock.withLock { snapshot }

    fun addListener(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
        listener(currentSnapshot())
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }

    fun updateBackend(status: ConnectionStatus) {
        mutate { it.copy(backend = status) }
    }

    fun updateVoice(status: ConnectionStatus) {
        mutate { it.copy(voice = status) }
    }

    fun updateVoiceRuntime(voiceRuntime: VoiceRuntimeState) {
        mutate { it.copy(voiceRuntime = voiceRuntime) }
    }

    fun updatePcControl(status: ConnectionStatus) {
        mutate { it.copy(pcControl = status) }
    }

    fun recordEvent(
        source: EventSource,
        severity: EventSeverity,
        title: String,
        details: String = ""
    ) {
        mutate { current ->
            current.copy(events = prependEvent(current.events, RuntimeEvent(source, severity, title, details, clock.instant())))
        }
    }

    fun consumePcStatus(message: String) {
        val normalized = message.trim()
        when {
            normalized.equals("Connected", ignoreCase = true) -> {
                updatePcControl(status(ConnectionState.CONNECTED, "Desktop action channel connected"))
                recordEvent(EventSource.PC_CONTROL, EventSeverity.SUCCESS, "Desktop actions connected")
            }
            normalized.equals("Disconnected", ignoreCase = true) -> {
                updatePcControl(status(ConnectionState.DISCONNECTED, "Desktop action channel disconnected"))
                recordEvent(EventSource.PC_CONTROL, EventSeverity.WARNING, "Desktop actions disconnected")
            }
            normalized.startsWith("Connecting", ignoreCase = true) ||
                normalized.startsWith("Reconnecting", ignoreCase = true) -> {
                updatePcControl(status(ConnectionState.CONNECTING, normalized))
            }
            normalized.equals("Connection failed", ignoreCase = true) -> {
                updatePcControl(status(ConnectionState.ERROR, "Desktop action channel connection failed"))
                recordEvent(EventSource.PC_CONTROL, EventSeverity.ERROR, "Desktop actions failed", normalized)
            }
            normalized.startsWith("Executing", ignoreCase = true) -> {
                updatePcControl(status(ConnectionState.CONNECTED, normalized))
                recordEvent(EventSource.PC_CONTROL, EventSeverity.INFO, normalized.removePrefix("Executing ").trim())
            }
            normalized.startsWith("✓") -> {
                updatePcControl(status(ConnectionState.CONNECTED, normalized))
                recordEvent(EventSource.PC_CONTROL, EventSeverity.SUCCESS, normalized.removePrefix("✓").trim())
            }
            normalized.startsWith("✗") -> {
                updatePcControl(status(ConnectionState.ERROR, normalized))
                recordEvent(EventSource.PC_CONTROL, EventSeverity.ERROR, normalized.removePrefix("✗").trim())
            }
            normalized.isNotBlank() -> updatePcControl(status(ConnectionState.UNKNOWN, normalized))
        }
    }

    fun consumeVoiceStatus(message: String) {
        val normalized = message.trim()
        when {
            normalized.equals("CONNECTED", ignoreCase = true) ||
                normalized.equals("Connected", ignoreCase = true) -> {
                updateVoice(status(ConnectionState.CONNECTED, "Voice channel connected"))
                recordEvent(EventSource.VOICE, EventSeverity.SUCCESS, "Voice connected")
            }
            normalized.equals("DISCONNECTED", ignoreCase = true) ||
                normalized.equals("Disconnected", ignoreCase = true) -> {
                updateVoice(status(ConnectionState.DISCONNECTED, "Voice channel disconnected"))
                recordEvent(EventSource.VOICE, EventSeverity.WARNING, "Voice disconnected")
            }
            normalized.startsWith("Reconnecting", ignoreCase = true) -> {
                updateVoice(status(ConnectionState.CONNECTING, normalized))
            }
            normalized.equals("LISTENING", ignoreCase = true) ||
                normalized.equals("PROCESSING", ignoreCase = true) ||
                normalized.equals("COOLDOWN", ignoreCase = true) ||
                normalized.equals("TTS_PLAYING", ignoreCase = true) ||
                normalized.equals("IDLE", ignoreCase = true) -> {
                updateVoice(status(ConnectionState.CONNECTED, "Voice state: $normalized"))
            }
            normalized.isNotBlank() -> updateVoice(status(ConnectionState.UNKNOWN, normalized))
        }
    }

    fun recordAssistantResponse(text: String, action: String?, handled: Boolean) {
        val title = if (handled) {
            action?.let { "Assistant handled: $it" } ?: "Assistant response"
        } else {
            "Assistant fallback"
        }
        recordEvent(
            source = EventSource.ASSISTANT,
            severity = if (handled) EventSeverity.SUCCESS else EventSeverity.INFO,
            title = title,
            details = text
        )
    }

    private fun initialStatus(detail: String): ConnectionStatus = status(ConnectionState.UNKNOWN, detail)

    private fun status(state: ConnectionState, detail: String): ConnectionStatus {
        return ConnectionStatus(state = state, detail = detail, updatedAt = clock.instant())
    }

    private fun mutate(transform: (Snapshot) -> Snapshot) {
        val updated = lock.withLock {
            snapshot = transform(snapshot)
            snapshot
        }
        listeners.forEach { it(updated) }
    }

    private fun prependEvent(
        current: List<RuntimeEvent>,
        event: RuntimeEvent
    ): List<RuntimeEvent> {
        return listOf(event) + current.take(maxEvents - 1)
    }
}
