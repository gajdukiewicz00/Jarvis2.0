package org.jarvis.desktop.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
                    apiGatewayBaseUrl = "http://127.0.0.1:8080",
                    reason = "runtime summary is active"
                )
            }
        )

        val updated = service.saveSettings("https://api.jarvis.local", Locale.ENGLISH, manualEndpointOverride = false)

        assertEquals("http://127.0.0.1:8080", updated.apiGatewayBaseUrl)
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
            apiGatewayBaseUrl = "http://127.0.0.1:8080",
            reason = "Active local runtime detected from last-run.json"
        )

        val refreshed = service.current()

        assertEquals("http://127.0.0.1:8080", refreshed.apiGatewayBaseUrl)
        assertEquals("ws://127.0.0.1:8080/ws/voice", refreshed.voiceWebSocketUrl)
        assertEquals("ws://127.0.0.1:8080/ws/pc-control", refreshed.pcControlWebSocketUrl)
        assertEquals(ConfigSource.ACTIVE_LOCAL_RUNTIME, refreshed.apiGatewaySource)
        assertTrue(refreshed.apiGatewayReason.contains("last-run.json"))
    }

    @Test
    fun `runtime summary fingerprint refreshes fallback after local runtime stops`() {
        val store = InMemoryDesktopSettingsStore(DesktopSettings(endpointSelectionMode = EndpointSelectionMode.AUTO))
        val runtimeState = MutableRuntimeState(
            fingerprint = "last-run-ready",
            snapshot = LocalRuntimeEndpointSnapshot(
                apiGatewayBaseUrl = "http://127.0.0.1:8080",
                reason = "Active local runtime detected from last-run.json"
            )
        )
        val service = DesktopConfigService(
            settingsStore = store,
            environmentProvider = { mapOf("JARVIS_RUNTIME_MODE" to "local") },
            localRuntimeEndpointProvider = { runtimeState.snapshot },
            runtimeSummaryFingerprintProvider = { runtimeState.fingerprint }
        )

        assertEquals("http://127.0.0.1:8080", service.current().apiGatewayBaseUrl)

        runtimeState.fingerprint = "last-run-stopped"
        runtimeState.snapshot = null

        val refreshed = service.current()

        assertEquals("http://127.0.0.1:8080", refreshed.apiGatewayBaseUrl)
        assertEquals("ws://127.0.0.1:8080/ws/voice", refreshed.voiceWebSocketUrl)
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
            apiGatewayBaseUrl = "http://127.0.0.1:8080",
            reason = "Active local runtime detected from last-run.json"
        )

        val refreshed = service.current()

        assertEquals("http://127.0.0.1:8080", refreshed.apiGatewayBaseUrl)
        assertEquals("ws://127.0.0.1:8080/ws/voice", refreshed.voiceWebSocketUrl)
        assertEquals("ws://127.0.0.1:8080/ws/pc-control", refreshed.pcControlWebSocketUrl)
        assertEquals(ConfigSource.ACTIVE_LOCAL_RUNTIME, refreshed.apiGatewaySource)
        assertEquals(2, notifications.size)
        assertEquals("ws://127.0.0.1:8080/ws/voice", notifications.last().voiceWebSocketUrl)
        assertEquals("ws://127.0.0.1:8080/ws/pc-control", notifications.last().pcControlWebSocketUrl)

        runtimeState.fingerprint = "last-run-ready-again"
        runtimeState.snapshot = LocalRuntimeEndpointSnapshot(
            apiGatewayBaseUrl = "http://127.0.0.1:8080",
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
            apiGatewayBaseUrl = "http://127.0.0.1:8080",
            reason = "Active local runtime detected from last-run.json"
        )

        val current = service.current()

        assertEquals("https://api.jarvis.local", current.apiGatewayBaseUrl)
        assertEquals(ConfigSource.MANUAL_PERSISTED_SETTINGS, current.apiGatewaySource)
        assertEquals(2, runtimeLookups.get(), "fingerprint changes should still be re-evaluated for stale endpoint recovery")
        assertEquals(2, notifications.size)
        assertEquals(ConfigSource.MANUAL_PERSISTED_SETTINGS, notifications.last().apiGatewaySource)
        assertTrue(notifications.last().apiGatewayReason.contains("was intentionally bypassed"))
    }

    @Test
    fun `stale manual localhost override is recovered to active k8s runtime`() {
        val service = DesktopConfigService(
            settingsStore = InMemoryDesktopSettingsStore(
                DesktopSettings(
                    apiGatewayBaseUrl = "http://localhost:56747",
                    localeTag = "en-US",
                    endpointSelectionMode = EndpointSelectionMode.MANUAL
                )
            ),
            environmentProvider = { emptyMap() },
            localRuntimeEndpointProvider = {
                LocalRuntimeEndpointSnapshot(
                    apiGatewayBaseUrl = "https://api.jarvis.local",
                    reason = "Active k8s runtime detected from last-run.json",
                    runtimeMode = RuntimeEndpointMode.K8S
                )
            },
            runtimeSummaryFingerprintProvider = { "last-run-k8s-ready" },
            endpointHealthProbe = { false }
        )

        val resolved = service.current()

        assertEquals("https://api.jarvis.local", resolved.apiGatewayBaseUrl)
        assertEquals("wss://api.jarvis.local/ws/voice", resolved.voiceWebSocketUrl)
        assertEquals(ConfigSource.ACTIVE_K8S_RUNTIME, resolved.apiGatewaySource)
        assertFalse(resolved.usesManualEndpointOverride)
        assertTrue(resolved.apiGatewayReason.contains("Recovered from stale manual localhost endpoint"))
    }

    @Test
    fun `manual localhost override recovers after runtime summary switches to healthy k8s`() {
        val runtimeState = MutableRuntimeState()
        val service = DesktopConfigService(
            settingsStore = InMemoryDesktopSettingsStore(
                DesktopSettings(
                    apiGatewayBaseUrl = "http://localhost:56747",
                    localeTag = "en-US",
                    endpointSelectionMode = EndpointSelectionMode.MANUAL
                )
            ),
            environmentProvider = { emptyMap() },
            localRuntimeEndpointProvider = { runtimeState.snapshot },
            runtimeSummaryFingerprintProvider = { runtimeState.fingerprint },
            endpointHealthProbe = { false }
        )

        val initial = service.current()
        assertEquals("http://localhost:56747", initial.apiGatewayBaseUrl)
        assertEquals(ConfigSource.MANUAL_PERSISTED_SETTINGS, initial.apiGatewaySource)

        runtimeState.fingerprint = "last-run-k8s-ready"
        runtimeState.snapshot = LocalRuntimeEndpointSnapshot(
            apiGatewayBaseUrl = "https://api.jarvis.local",
            reason = "Active k8s runtime detected from last-run.json",
            runtimeMode = RuntimeEndpointMode.K8S
        )

        val recovered = service.current()

        assertEquals("https://api.jarvis.local", recovered.apiGatewayBaseUrl)
        assertEquals(ConfigSource.ACTIVE_K8S_RUNTIME, recovered.apiGatewaySource)
        assertFalse(recovered.usesManualEndpointOverride)
        assertTrue(recovered.apiGatewayReason.contains("Recovered from stale manual localhost endpoint"))
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
