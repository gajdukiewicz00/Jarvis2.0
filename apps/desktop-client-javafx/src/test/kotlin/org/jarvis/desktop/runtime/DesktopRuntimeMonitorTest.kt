package org.jarvis.desktop.runtime

import org.jarvis.desktop.model.VoiceRuntimeState
import org.jarvis.desktop.model.VoiceState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DesktopRuntimeMonitorTest {

    private val clock = Clock.fixed(Instant.parse("2026-03-14T10:15:30Z"), ZoneOffset.UTC)

    @Test
    fun `consume pc and voice status updates connection states and assistant events`() {
        val monitor = DesktopRuntimeMonitor(clock = clock, maxEvents = 5)

        monitor.consumePcStatus("Connected")
        monitor.consumeVoiceStatus("CONNECTED")
        monitor.recordAssistantResponse("Reminder delivered", "reminder", handled = true)

        val snapshot = monitor.currentSnapshot()
        assertEquals(DesktopRuntimeMonitor.ConnectionState.CONNECTED, snapshot.pcControl.state)
        assertEquals(DesktopRuntimeMonitor.ConnectionState.CONNECTED, snapshot.voice.state)
        assertEquals(DesktopRuntimeMonitor.ConnectionState.UNKNOWN, snapshot.backend.state)
        assertEquals("Assistant handled: reminder", snapshot.events.first().title)
        assertEquals(DesktopRuntimeMonitor.EventSource.ASSISTANT, snapshot.events.first().source)
    }

    @Test
    fun `event feed keeps newest entries within configured limit`() {
        val monitor = DesktopRuntimeMonitor(clock = clock, maxEvents = 2)

        monitor.recordEvent(DesktopRuntimeMonitor.EventSource.SYSTEM, DesktopRuntimeMonitor.EventSeverity.INFO, "first")
        monitor.recordEvent(DesktopRuntimeMonitor.EventSource.SYSTEM, DesktopRuntimeMonitor.EventSeverity.INFO, "second")
        monitor.recordEvent(DesktopRuntimeMonitor.EventSource.SYSTEM, DesktopRuntimeMonitor.EventSeverity.INFO, "third")

        val titles = monitor.currentSnapshot().events.map { it.title }
        assertEquals(listOf("third", "second"), titles)
    }

    @Test
    fun `pc error status records actionable error event`() {
        val monitor = DesktopRuntimeMonitor(clock = clock, maxEvents = 3)

        monitor.consumePcStatus("✗ Notification delivery failed")

        val snapshot = monitor.currentSnapshot()
        assertEquals(DesktopRuntimeMonitor.ConnectionState.ERROR, snapshot.pcControl.state)
        assertEquals(DesktopRuntimeMonitor.EventSeverity.ERROR, snapshot.events.first().severity)
        assertTrue(snapshot.events.first().title.contains("Notification delivery failed"))
    }

    @Test
    fun `voiceRuntime starts null and is populated via updateVoiceRuntime`() {
        val monitor = DesktopRuntimeMonitor(clock = clock)
        assertNull(monitor.currentSnapshot().voiceRuntime)

        val voiceState = VoiceRuntimeState(
            sessionState = VoiceState.LISTENING,
            connectionPhase = VoiceRuntimeState.ConnectionPhase.CONNECTED,
            pushToTalkActive = true,
            alwaysListeningActive = false,
            currentCorrelationId = "test-123",
            inputDevice = VoiceRuntimeState.AudioDeviceInfo("Test Mic", true),
            outputDevice = null,
            lastError = null,
            updatedAt = clock.instant()
        )
        monitor.updateVoiceRuntime(voiceState)

        val snapshot = monitor.currentSnapshot()
        assertNotNull(snapshot.voiceRuntime)
        assertEquals(VoiceState.LISTENING, snapshot.voiceRuntime!!.sessionState)
        assertTrue(snapshot.voiceRuntime!!.pushToTalkActive)
        assertEquals("Test Mic", snapshot.voiceRuntime!!.inputDevice?.name)
    }
}
