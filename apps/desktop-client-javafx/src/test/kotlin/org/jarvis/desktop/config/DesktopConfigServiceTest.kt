package org.jarvis.desktop.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class DesktopConfigServiceTest {

    @Test
    fun `saving settings updates resolved runtime endpoints`() {
        val store = InMemoryDesktopSettingsStore()
        val service = DesktopConfigService(
            settingsStore = store,
            environmentProvider = { mapOf("JARVIS_API_BASE_URL" to "https://api.jarvis.local") },
            localRuntimeEndpointProvider = { null }
        )

        val initial = service.current()
        assertEquals("https://api.jarvis.local", initial.apiGatewayBaseUrl)

        val updated = service.saveSettings("http://127.0.0.1:8080", Locale("pl", "PL"), manualEndpointOverride = true)

        assertEquals("http://127.0.0.1:8080", updated.apiGatewayBaseUrl)
        assertEquals("ws://127.0.0.1:8080/ws/voice", updated.voiceWebSocketUrl)
        assertEquals("ws://127.0.0.1:8080/ws/pc-control", updated.pcControlWebSocketUrl)
        assertEquals(ConfigSource.MANUAL_PERSISTED_SETTINGS, updated.apiGatewaySource)
        assertEquals("pl-PL", store.settings.localeTag)
        assertEquals(EndpointSelectionMode.MANUAL, store.settings.endpointSelectionMode)
    }

    @Test
    fun `automatic mode clears persisted endpoint override`() {
        val store = InMemoryDesktopSettingsStore(
            DesktopSettings(
                apiGatewayBaseUrl = "https://api.jarvis.local",
                localeTag = "en-US",
                endpointSelectionMode = EndpointSelectionMode.MANUAL
            )
        )
        val service = DesktopConfigService(
            settingsStore = store,
            environmentProvider = { emptyMap() },
            localRuntimeEndpointProvider = {
                LocalRuntimeEndpointSnapshot(
                    apiGatewayBaseUrl = "https://127.0.0.1:18080",
                    reason = "runtime summary is active"
                )
            }
        )

        val updated = service.saveSettings("https://api.jarvis.local", Locale.ENGLISH, manualEndpointOverride = false)

        assertEquals("https://127.0.0.1:18080", updated.apiGatewayBaseUrl)
        assertEquals(ConfigSource.ACTIVE_LOCAL_RUNTIME, updated.apiGatewaySource)
        assertEquals(null, store.settings.apiGatewayBaseUrl)
        assertEquals(EndpointSelectionMode.AUTO, store.settings.endpointSelectionMode)
    }

    @Test
    fun `auto mode refreshes cached endpoints when runtime summary changes`() {
        val store = InMemoryDesktopSettingsStore(
            DesktopSettings(
                apiGatewayBaseUrl = "https://api.jarvis.local",
                localeTag = "en-US",
                endpointSelectionMode = EndpointSelectionMode.AUTO
            )
        )
        val runtimeState = MutableRuntimeState()
        val service = DesktopConfigService(
            settingsStore = store,
            environmentProvider = { emptyMap() },
            localRuntimeEndpointProvider = { runtimeState.snapshot },
            runtimeSummaryFingerprintProvider = { runtimeState.fingerprint }
        )

        val initial = service.current()
        assertEquals("https://api.jarvis.local", initial.apiGatewayBaseUrl)
        assertEquals(ConfigSource.LEGACY_PERSISTED_SETTINGS, initial.apiGatewaySource)

        runtimeState.fingerprint = "last-run-ready"
        runtimeState.snapshot = LocalRuntimeEndpointSnapshot(
            apiGatewayBaseUrl = "https://127.0.0.1:18080",
            reason = "Active local runtime detected from last-run.json"
        )

        val refreshed = service.current()

        assertEquals("https://127.0.0.1:18080", refreshed.apiGatewayBaseUrl)
        assertEquals("wss://127.0.0.1:18080/ws/voice", refreshed.voiceWebSocketUrl)
        assertEquals("wss://127.0.0.1:18080/ws/pc-control", refreshed.pcControlWebSocketUrl)
        assertEquals(ConfigSource.ACTIVE_LOCAL_RUNTIME, refreshed.apiGatewaySource)
        assertTrue(refreshed.apiGatewayReason.contains("last-run.json"))
    }

    @Test
    fun `runtime summary fingerprint refreshes fallback after local runtime stops`() {
        val store = InMemoryDesktopSettingsStore(DesktopSettings(endpointSelectionMode = EndpointSelectionMode.AUTO))
        val runtimeState = MutableRuntimeState(
            fingerprint = "last-run-ready",
            snapshot = LocalRuntimeEndpointSnapshot(
                apiGatewayBaseUrl = "https://127.0.0.1:18080",
                reason = "Active local runtime detected from last-run.json"
            )
        )
        val service = DesktopConfigService(
            settingsStore = store,
            environmentProvider = { mapOf("JARVIS_RUNTIME_MODE" to "local") },
            localRuntimeEndpointProvider = { runtimeState.snapshot },
            runtimeSummaryFingerprintProvider = { runtimeState.fingerprint }
        )

        assertEquals("https://127.0.0.1:18080", service.current().apiGatewayBaseUrl)

        runtimeState.fingerprint = "last-run-stopped"
        runtimeState.snapshot = null

        val refreshed = service.current()

        assertEquals("https://127.0.0.1:18080", refreshed.apiGatewayBaseUrl)
        assertEquals("wss://127.0.0.1:18080/ws/voice", refreshed.voiceWebSocketUrl)
        assertEquals(ConfigSource.DEFAULT_LOCAL, refreshed.apiGatewaySource)
        assertTrue(refreshed.apiGatewayReason.contains("Falling back to local runtime default"))
    }

    @Test
    fun `auto mode listener follows runtime transition and suppresses redundant notifications`() {
        val store = InMemoryDesktopSettingsStore(
            DesktopSettings(
                apiGatewayBaseUrl = "https://api.jarvis.local",
                localeTag = "en-US",
                endpointSelectionMode = EndpointSelectionMode.AUTO
            )
        )
        val runtimeState = MutableRuntimeState()
        val service = DesktopConfigService(
            settingsStore = store,
            environmentProvider = { emptyMap() },
            localRuntimeEndpointProvider = { runtimeState.snapshot },
            runtimeSummaryFingerprintProvider = { runtimeState.fingerprint }
        )
        val notifications = mutableListOf<ResolvedDesktopConfig>()

        service.addListener { notifications += it }

        assertEquals(1, notifications.size)
        assertEquals(ConfigSource.LEGACY_PERSISTED_SETTINGS, notifications.single().apiGatewaySource)
        assertEquals("https://api.jarvis.local", notifications.single().apiGatewayBaseUrl)

        runtimeState.fingerprint = "last-run-ready"
        runtimeState.snapshot = LocalRuntimeEndpointSnapshot(
            apiGatewayBaseUrl = "https://127.0.0.1:18080",
            reason = "Active local runtime detected from last-run.json"
        )

        val refreshed = service.current()

        assertEquals("https://127.0.0.1:18080", refreshed.apiGatewayBaseUrl)
        assertEquals("wss://127.0.0.1:18080/ws/voice", refreshed.voiceWebSocketUrl)
        assertEquals("wss://127.0.0.1:18080/ws/pc-control", refreshed.pcControlWebSocketUrl)
        assertEquals(ConfigSource.ACTIVE_LOCAL_RUNTIME, refreshed.apiGatewaySource)
        assertEquals(2, notifications.size)
        assertEquals("wss://127.0.0.1:18080/ws/voice", notifications.last().voiceWebSocketUrl)
        assertEquals("wss://127.0.0.1:18080/ws/pc-control", notifications.last().pcControlWebSocketUrl)

        runtimeState.fingerprint = "last-run-ready-again"
        runtimeState.snapshot = LocalRuntimeEndpointSnapshot(
            apiGatewayBaseUrl = "https://127.0.0.1:18080",
            reason = "Active local runtime detected from last-run.json"
        )

        val stable = service.current()

        assertEquals(refreshed, stable)
        assertEquals(2, notifications.size)
    }

    @Test
    fun `manual mode ignores runtime summary churn after startup`() {
        val runtimeState = MutableRuntimeState()
        val runtimeLookups = AtomicInteger(0)
        val service = DesktopConfigService(
            settingsStore = InMemoryDesktopSettingsStore(
                DesktopSettings(
                    apiGatewayBaseUrl = "https://api.jarvis.local",
                    localeTag = "en-US",
                    endpointSelectionMode = EndpointSelectionMode.MANUAL
                )
            ),
            environmentProvider = { emptyMap() },
            localRuntimeEndpointProvider = {
                runtimeLookups.incrementAndGet()
                runtimeState.snapshot
            },
            runtimeSummaryFingerprintProvider = { runtimeState.fingerprint }
        )
        val notifications = mutableListOf<ResolvedDesktopConfig>()

        service.addListener { notifications += it }

        assertEquals(1, runtimeLookups.get(), "manual mode should only resolve once during startup")
        assertEquals(1, notifications.size)
        assertEquals(ConfigSource.MANUAL_PERSISTED_SETTINGS, notifications.single().apiGatewaySource)

        runtimeState.fingerprint = "last-run-ready"
        runtimeState.snapshot = LocalRuntimeEndpointSnapshot(
            apiGatewayBaseUrl = "https://127.0.0.1:18080",
            reason = "Active local runtime detected from last-run.json"
        )

        val current = service.current()

        assertEquals("https://api.jarvis.local", current.apiGatewayBaseUrl)
        assertEquals(ConfigSource.MANUAL_PERSISTED_SETTINGS, current.apiGatewaySource)
        assertEquals(1, runtimeLookups.get(), "manual mode should not re-resolve from runtime-summary changes")
        assertEquals(1, notifications.size)
    }

    private class InMemoryDesktopSettingsStore(
        var settings: DesktopSettings = DesktopSettings()
    ) : DesktopSettingsStore {
        override fun load(): DesktopSettings = settings

        override fun save(settings: DesktopSettings) {
            this.settings = settings
        }
    }

    private data class MutableRuntimeState(
        var fingerprint: String? = null,
        var snapshot: LocalRuntimeEndpointSnapshot? = null
    )
}
