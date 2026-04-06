package org.jarvis.desktop.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

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

    private class InMemoryDesktopSettingsStore(
        var settings: DesktopSettings = DesktopSettings()
    ) : DesktopSettingsStore {
        override fun load(): DesktopSettings = settings

        override fun save(settings: DesktopSettings) {
            this.settings = settings
        }
    }
}
