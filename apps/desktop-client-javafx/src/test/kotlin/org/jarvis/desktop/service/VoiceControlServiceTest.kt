package org.jarvis.desktop.service

import org.jarvis.desktop.model.VoiceRuntimeState
import org.jarvis.desktop.model.VoiceRuntimeState.AudioDeviceInfo
import org.jarvis.desktop.model.VoiceRuntimeState.ConnectionPhase
import org.jarvis.desktop.model.VoiceState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class VoiceControlServiceTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-03-14T10:00:00Z"), ZoneOffset.UTC)
    private lateinit var session: VoiceSession
    private lateinit var wsClient: VoiceWebSocketClient
    private lateinit var service: VoiceControlService

    private val sessionStarted = AtomicBoolean(false)
    private val sessionCancelled = AtomicReference<String>()
    private val lastWsCommand = AtomicReference<String>()

    @BeforeEach
    fun setup() {
        sessionStarted.set(false)
        sessionCancelled.set(null)
        lastWsCommand.set(null)

        session = VoiceSession(
            onStateChange = { _, _ -> },
            onStartRecording = {},
            onStopRecording = {},
            onSendEndOfSpeech = {},
            onEnableWakeWord = {},
            onDisableWakeWord = {},
            onPauseMedia = {},
            onResumeMedia = {},
            onSpeakTimeout = {},
            onSessionError = { _, _ -> }
        )

        wsClient = VoiceWebSocketClient(
            url = "ws://localhost:0/ws/voice",
            onStateChange = {},
            onTranscript = { _, _, _ -> },
            onResponse = { _, _, _ -> },
            onAudioReceived = {}
        )

        service = VoiceControlService(
            voiceSession = session,
            webSocketClient = wsClient,
            clock = fixedClock,
            audioDeviceDetector = {
                VoiceControlService.AudioDevicePair(
                    inputs = listOf(AudioDeviceInfo("Test Mic", true), AudioDeviceInfo("USB Mic", true)),
                    outputs = listOf(AudioDeviceInfo("Test Speaker", true))
                )
            }
        )
    }

    @Test
    @DisplayName("initial state is disconnected IDLE")
    fun initialState() {
        val state = service.currentState()
        assertEquals(VoiceState.IDLE, state.sessionState)
        assertEquals(ConnectionPhase.DISCONNECTED, state.connectionPhase)
        assertFalse(state.pushToTalkActive)
        assertFalse(state.alwaysListeningActive)
    }

    @Test
    @DisplayName("pushToTalkStart returns null when disconnected")
    fun pttStartFailsWhenDisconnected() {
        assertNull(service.pushToTalkStart())
        assertFalse(service.currentState().pushToTalkActive)
    }

    @Test
    @DisplayName("pushToTalkStart succeeds when connected and idle")
    fun pttStartSucceeds() {
        service.onConnectionStateChanged("CONNECTED")
        val correlationId = service.pushToTalkStart()
        assertNotNull(correlationId)
        assertTrue(service.currentState().pushToTalkActive)
        assertEquals(correlationId, service.currentState().currentCorrelationId)
    }

    @Test
    @DisplayName("pushToTalkRelease clears PTT flag when listening")
    fun pttRelease() {
        service.onConnectionStateChanged("CONNECTED")
        service.pushToTalkStart()
        assertTrue(service.currentState().pushToTalkActive)

        service.onSessionStateChanged(VoiceState.LISTENING, "some-id")
        service.pushToTalkRelease()
        assertFalse(service.currentState().pushToTalkActive)
    }

    @Test
    @DisplayName("cancelCurrentSession invokes session cancel and clears PTT")
    fun cancelSession() {
        service.onConnectionStateChanged("CONNECTED")
        service.pushToTalkStart()
        service.onSessionStateChanged(VoiceState.LISTENING, "some-id")

        service.cancelCurrentSession("test cancel")
        assertFalse(service.currentState().pushToTalkActive)
    }

    @Test
    @DisplayName("cancelCurrentSession is no-op when not cancellable")
    fun cancelNoOp() {
        service.cancelCurrentSession()
        assertEquals(VoiceState.IDLE, service.currentState().sessionState)
    }

    @Test
    @DisplayName("onSessionStateChanged updates session state")
    fun sessionStateChange() {
        service.onSessionStateChanged(VoiceState.PROCESSING, "corr-1")
        assertEquals(VoiceState.PROCESSING, service.currentState().sessionState)
        assertEquals("corr-1", service.currentState().currentCorrelationId)
    }

    @Test
    @DisplayName("onConnectionStateChanged maps known strings")
    fun connectionStateMapping() {
        service.onConnectionStateChanged("CONNECTED")
        assertEquals(ConnectionPhase.CONNECTED, service.currentState().connectionPhase)

        service.onConnectionStateChanged("DISCONNECTED")
        assertEquals(ConnectionPhase.DISCONNECTED, service.currentState().connectionPhase)

        service.onConnectionStateChanged("Reconnecting in 4s...")
        assertEquals(ConnectionPhase.RECONNECTING, service.currentState().connectionPhase)

        service.onConnectionStateChanged("ERROR: server down")
        assertEquals(ConnectionPhase.FAILED, service.currentState().connectionPhase)
        assertEquals("server down", service.currentState().lastError)

        service.onConnectionStateChanged("Connection failed")
        assertEquals(ConnectionPhase.FAILED, service.currentState().connectionPhase)
    }

    @Test
    @DisplayName("onAlwaysListeningChanged updates flag")
    fun alwaysListeningFlag() {
        service.onAlwaysListeningChanged(true)
        assertTrue(service.currentState().alwaysListeningActive)

        service.onAlwaysListeningChanged(false)
        assertFalse(service.currentState().alwaysListeningActive)
    }

    @Test
    @DisplayName("onError sets lastError")
    fun errorTracking() {
        service.onError("mic unavailable")
        assertEquals("mic unavailable", service.currentState().lastError)
    }

    @Test
    @DisplayName("refreshDevices populates device info and lists")
    fun deviceDetection() {
        service.refreshDevices()
        val state = service.currentState()
        assertNotNull(state.inputDevice)
        assertEquals("Test Mic", state.inputDevice?.name)
        assertNotNull(state.outputDevice)
        assertEquals("Test Speaker", state.outputDevice?.name)
        assertEquals(2, state.availableInputDevices.size)
        assertEquals(1, state.availableOutputDevices.size)
        assertEquals("USB Mic", state.availableInputDevices[1].name)
    }

    @Test
    @DisplayName("selectInputDevice switches active input from available list")
    fun selectInputDevice() {
        service.refreshDevices()
        assertEquals("Test Mic", service.currentState().inputDevice?.name)

        service.selectInputDevice("USB Mic")
        assertEquals("USB Mic", service.currentState().inputDevice?.name)
    }

    @Test
    @DisplayName("selectInputDevice ignores unknown device name")
    fun selectUnknownDevice() {
        service.refreshDevices()
        service.selectInputDevice("Nonexistent Mic")
        assertEquals("Test Mic", service.currentState().inputDevice?.name)
    }

    @Test
    @DisplayName("refreshDevices preserves previous selection if still available")
    fun devicePreservesSelection() {
        service.refreshDevices()
        service.selectInputDevice("USB Mic")
        assertEquals("USB Mic", service.currentState().inputDevice?.name)

        service.refreshDevices()
        assertEquals("USB Mic", service.currentState().inputDevice?.name)
    }

    @Test
    @DisplayName("listeners are notified on state changes")
    fun listenerNotification() {
        val received = mutableListOf<VoiceRuntimeState>()
        service.addListener { received += it }

        assertEquals(1, received.size, "listener should receive initial state on subscribe")

        service.onConnectionStateChanged("CONNECTED")
        assertEquals(2, received.size)
        assertEquals(ConnectionPhase.CONNECTED, received.last().connectionPhase)
    }

    @Test
    @DisplayName("removed listener stops receiving updates")
    fun listenerRemoval() {
        val received = mutableListOf<VoiceRuntimeState>()
        val listener: (VoiceRuntimeState) -> Unit = { received += it }
        service.addListener(listener)
        service.removeListener(listener)

        service.onConnectionStateChanged("CONNECTED")
        assertEquals(1, received.size, "should only have the initial snapshot")
    }

    @Test
    @DisplayName("refreshDevices handles detector failure gracefully")
    fun deviceDetectionFailure() {
        val failing = VoiceControlService(
            voiceSession = session,
            webSocketClient = wsClient,
            clock = fixedClock,
            audioDeviceDetector = { throw RuntimeException("no sound system") }
        )
        failing.refreshDevices()
        assertNull(failing.currentState().inputDevice)
        assertNull(failing.currentState().outputDevice)
        assertTrue(failing.currentState().availableInputDevices.isEmpty())
        assertTrue(failing.currentState().availableOutputDevices.isEmpty())
    }

    // ── connection-loss robustness ──────────────────────────────────

    @Test
    @DisplayName("connection loss during busy session resets to idle")
    fun connectionLossResetsBusySession() {
        service.onConnectionStateChanged("CONNECTED")
        service.onSessionStateChanged(VoiceState.LISTENING, "corr-1")

        service.onConnectionStateChanged("DISCONNECTED")

        val state = service.currentState()
        assertEquals(VoiceState.IDLE, state.sessionState)
        assertEquals(ConnectionPhase.DISCONNECTED, state.connectionPhase)
        assertFalse(state.pushToTalkActive)
        assertNull(state.currentCorrelationId)
    }

    @Test
    @DisplayName("connection loss clears pushToTalkActive flag")
    fun connectionLossClearsPtt() {
        service.onConnectionStateChanged("CONNECTED")
        service.pushToTalkStart()
        service.onSessionStateChanged(VoiceState.LISTENING, "corr-1")
        assertTrue(service.currentState().pushToTalkActive)

        service.onConnectionStateChanged("DISCONNECTED")

        assertFalse(service.currentState().pushToTalkActive)
    }

    @Test
    @DisplayName("connection failure during TTS resets session state")
    fun connectionFailureDuringTts() {
        service.onConnectionStateChanged("CONNECTED")
        service.onSessionStateChanged(VoiceState.TTS_PLAYBACK, "corr-2")

        service.onConnectionStateChanged("ERROR: gateway unreachable")

        assertEquals(VoiceState.IDLE, service.currentState().sessionState)
        assertEquals(ConnectionPhase.FAILED, service.currentState().connectionPhase)
        assertEquals("gateway unreachable", service.currentState().lastError)
    }

    @Test
    @DisplayName("connection loss during idle does not change session state")
    fun connectionLossIdleUnchanged() {
        service.onConnectionStateChanged("CONNECTED")

        service.onConnectionStateChanged("DISCONNECTED")

        assertEquals(VoiceState.IDLE, service.currentState().sessionState)
        assertEquals(ConnectionPhase.DISCONNECTED, service.currentState().connectionPhase)
    }

    @Test
    @DisplayName("reconnect after connection-loss reset allows new session")
    fun reconnectAfterReset() {
        service.onConnectionStateChanged("CONNECTED")
        service.onSessionStateChanged(VoiceState.PROCESSING, "corr-1")

        service.onConnectionStateChanged("DISCONNECTED")
        assertEquals(VoiceState.IDLE, service.currentState().sessionState)

        service.onConnectionStateChanged("CONNECTED")
        assertTrue(service.currentState().canStartSession)
    }

    @Test
    @DisplayName("non-usable to non-usable transition does not re-reset")
    fun nonUsableToNonUsable() {
        service.onConnectionStateChanged("DISCONNECTED")
        service.onSessionStateChanged(VoiceState.IDLE, null)

        service.onConnectionStateChanged("ERROR: something")

        assertEquals(VoiceState.IDLE, service.currentState().sessionState)
        assertEquals(ConnectionPhase.FAILED, service.currentState().connectionPhase)
    }

    // ── rapid action safety ─────────────────────────────────────────

    @Test
    @DisplayName("double pushToTalkRelease is safe and idempotent")
    fun doublePttRelease() {
        service.onConnectionStateChanged("CONNECTED")
        service.pushToTalkStart()
        service.onSessionStateChanged(VoiceState.LISTENING, "some-id")

        service.pushToTalkRelease()
        assertFalse(service.currentState().pushToTalkActive)

        service.pushToTalkRelease()
        assertFalse(service.currentState().pushToTalkActive)
    }

    @Test
    @DisplayName("pushToTalkRelease when idle does not notify listeners")
    fun pttReleaseNoOpSkipsNotification() {
        val received = mutableListOf<VoiceRuntimeState>()
        service.addListener { received += it }
        val before = received.size

        service.pushToTalkRelease()

        assertEquals(before, received.size, "no-op release should not notify")
    }

    @Test
    @DisplayName("repeated identical error does not trigger extra notification")
    fun repeatedIdenticalError() {
        val received = mutableListOf<VoiceRuntimeState>()
        service.addListener { received += it }
        val before = received.size

        service.onError("mic broken")
        assertEquals(before + 1, received.size)

        service.onError("mic broken")
        assertEquals(before + 1, received.size, "same error should not notify again")
    }

    @Test
    @DisplayName("different error after same error does trigger notification")
    fun differentErrorNotifies() {
        val received = mutableListOf<VoiceRuntimeState>()
        service.addListener { received += it }
        val before = received.size

        service.onError("mic broken")
        service.onError("connection lost")
        assertEquals(before + 2, received.size)
    }

    @Test
    @DisplayName("selectOutputDevice switches active output")
    fun selectOutputDevice() {
        val multiOutput = VoiceControlService(
            voiceSession = session,
            webSocketClient = wsClient,
            clock = fixedClock,
            audioDeviceDetector = {
                VoiceControlService.AudioDevicePair(
                    inputs = listOf(AudioDeviceInfo("Mic", true)),
                    outputs = listOf(AudioDeviceInfo("Speakers", true), AudioDeviceInfo("HDMI", true))
                )
            }
        )
        multiOutput.refreshDevices()
        assertEquals("Speakers", multiOutput.currentState().outputDevice?.name)

        multiOutput.selectOutputDevice("HDMI")
        assertEquals("HDMI", multiOutput.currentState().outputDevice?.name)
    }
}
