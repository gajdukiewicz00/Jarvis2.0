package org.jarvis.desktop.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopConfigResolverTest {

    @Test
    fun `resolve falls back to remote ingress default when nothing else is configured`() {
        val config = DesktopConfigResolver.resolve(environment = emptyMap(), settings = DesktopSettings())

        assertEquals("https://api.jarvis.local", config.apiGatewayBaseUrl)
        assertEquals(ConfigSource.DEFAULT_INGRESS, config.apiGatewaySource)
        assertFalse(config.usesManualEndpointOverride)
        assertEquals("wss://api.jarvis.local/ws/voice", config.voiceWebSocketUrl)
    }

    @Test
    fun `resolve falls back to local default when runtime mode is local`() {
        val config = DesktopConfigResolver.resolve(
            environment = mapOf("JARVIS_RUNTIME_MODE" to "local"),
            settings = DesktopSettings()
        )

        assertEquals("http://127.0.0.1:8080", config.apiGatewayBaseUrl)
        assertEquals(ConfigSource.DEFAULT_LOCAL, config.apiGatewaySource)
        assertEquals("ws://127.0.0.1:8080/ws/voice", config.voiceWebSocketUrl)
    }

    @Test
    fun `resolve honors explicit JARVIS_API_BASE_URL env override`() {
        val config = DesktopConfigResolver.resolve(
            environment = mapOf("JARVIS_API_BASE_URL" to "http://192.168.1.50:8080"),
            settings = DesktopSettings()
        )

        assertEquals("http://192.168.1.50:8080", config.apiGatewayBaseUrl)
        assertEquals(ConfigSource.ENV_JARVIS_API_BASE_URL, config.apiGatewaySource)
    }

    @Test
    fun `resolve prefers JARVIS_API_BASE_URL over API_URL`() {
        val config = DesktopConfigResolver.resolve(
            environment = mapOf(
                "JARVIS_API_BASE_URL" to "http://10.0.0.1:8080",
                "API_URL" to "http://10.0.0.2:8080"
            ),
            settings = DesktopSettings()
        )

        assertEquals("http://10.0.0.1:8080", config.apiGatewayBaseUrl)
    }

    @Test
    fun `resolve falls back to API_URL when JARVIS_API_BASE_URL is absent`() {
        val config = DesktopConfigResolver.resolve(
            environment = mapOf("API_URL" to "http://10.0.0.2:8080"),
            settings = DesktopSettings()
        )

        assertEquals("http://10.0.0.2:8080", config.apiGatewayBaseUrl)
        assertEquals(ConfigSource.ENV_API_URL, config.apiGatewaySource)
    }

    @Test
    fun `resolve honors manual persisted endpoint override`() {
        val config = DesktopConfigResolver.resolve(
            environment = emptyMap(),
            settings = DesktopSettings(
                apiGatewayBaseUrl = "http://my-manual-host:9090",
                endpointSelectionMode = EndpointSelectionMode.MANUAL
            )
        )

        assertEquals("http://my-manual-host:9090", config.apiGatewayBaseUrl)
        assertEquals(ConfigSource.MANUAL_PERSISTED_SETTINGS, config.apiGatewaySource)
        assertTrue(config.usesManualEndpointOverride)
    }

    @Test
    fun `resolve recovers from a stale manual localhost pin when active runtime is k8s and unreachable`() {
        val localRuntime = LocalRuntimeEndpointSnapshot(
            apiGatewayBaseUrl = "https://api.jarvis.local",
            reason = "k8s runtime detected",
            runtimeMode = RuntimeEndpointMode.K8S
        )
        val config = DesktopConfigResolver.resolve(
            environment = emptyMap(),
            settings = DesktopSettings(
                apiGatewayBaseUrl = "http://127.0.0.1:8080",
                endpointSelectionMode = EndpointSelectionMode.MANUAL
            ),
            localRuntimeEndpoint = localRuntime,
            manualEndpointReachable = false
        )

        assertEquals("https://api.jarvis.local", config.apiGatewayBaseUrl)
        assertEquals(ConfigSource.ACTIVE_K8S_RUNTIME, config.apiGatewaySource)
        assertFalse(config.usesManualEndpointOverride)
        assertTrue(config.apiGatewayReason.contains("Recovered from stale manual localhost endpoint"))
    }

    @Test
    fun `resolve falls back to legacy persisted endpoint when selection mode is not manual`() {
        val config = DesktopConfigResolver.resolve(
            environment = emptyMap(),
            settings = DesktopSettings(apiGatewayBaseUrl = "http://legacy-host:8080")
        )

        assertEquals("http://legacy-host:8080", config.apiGatewayBaseUrl)
        assertEquals(ConfigSource.LEGACY_PERSISTED_SETTINGS, config.apiGatewaySource)
    }

    @Test
    fun `resolve uses active local runtime endpoint when detected and no manual override is pinned`() {
        val localRuntime = LocalRuntimeEndpointSnapshot(
            apiGatewayBaseUrl = "http://127.0.0.1:8080",
            reason = "local runtime detected",
            runtimeMode = RuntimeEndpointMode.LOCAL
        )
        val config = DesktopConfigResolver.resolve(
            environment = emptyMap(),
            settings = DesktopSettings(),
            localRuntimeEndpoint = localRuntime
        )

        assertEquals("http://127.0.0.1:8080", config.apiGatewayBaseUrl)
        assertEquals(ConfigSource.ACTIVE_LOCAL_RUNTIME, config.apiGatewaySource)
        assertTrue(config.apiGatewayReason.contains("no manual endpoint override is active"))
    }

    @Test
    fun `resolve throws when JARVIS_USE_TLS=true but the resolved URL is not https`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            DesktopConfigResolver.resolve(
                environment = mapOf(
                    "JARVIS_API_BASE_URL" to "http://10.0.0.1:8080",
                    "JARVIS_USE_TLS" to "true"
                ),
                settings = DesktopSettings()
            )
        }
        assertTrue(ex.message!!.contains("TLS mode requires"))
    }

    @Test
    fun `resolve throws when JARVIS_USE_TLS=false but URL contains jarvis-local domain`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            DesktopConfigResolver.resolve(
                environment = mapOf(
                    "JARVIS_API_BASE_URL" to "https://weird.jarvis.local",
                    "JARVIS_USE_TLS" to "false"
                ),
                settings = DesktopSettings()
            )
        }
        assertTrue(ex.message!!.contains("require TLS"))
    }

    @Test
    fun `normalizeBaseUrl strips trailing slash and api-v1 suffix and defaults to http scheme`() {
        // removeSuffix("/api/v1") only matches when "/api/v1" is literally the tail, so it
        // must come before any trailing slash — "…/api/v1/" only has the slash trimmed.
        assertEquals("http://myhost:8080", DesktopConfigResolver.normalizeBaseUrl("myhost:8080/api/v1"))
        assertEquals("http://myhost:8080/api/v1", DesktopConfigResolver.normalizeBaseUrl("myhost:8080/api/v1/"))
        assertEquals("https://api.jarvis.local", DesktopConfigResolver.normalizeBaseUrl("api.jarvis.local"))
        assertEquals("https://secure-host", DesktopConfigResolver.normalizeBaseUrl("https://secure-host/"))
        assertNull(DesktopConfigResolver.normalizeBaseUrl(null))
        assertNull(DesktopConfigResolver.normalizeBaseUrl("   "))
    }

    @Test
    fun `normalizePersistedSettings round trips manual override state`() {
        val settings = DesktopConfigResolver.normalizePersistedSettings(
            apiGatewayBaseUrl = "http://myhost:8080/",
            locale = java.util.Locale.forLanguageTag("ru-RU"),
            manualEndpointOverride = true
        )

        assertEquals("http://myhost:8080", settings.apiGatewayBaseUrl)
        assertEquals("ru-RU", settings.localeTag)
        assertEquals(EndpointSelectionMode.MANUAL, settings.endpointSelectionMode)
    }

    @Test
    fun `normalizePersistedSettings drops the base url when override is not manual`() {
        val settings = DesktopConfigResolver.normalizePersistedSettings(
            apiGatewayBaseUrl = "http://myhost:8080",
            locale = java.util.Locale.ENGLISH,
            manualEndpointOverride = false
        )

        assertNull(settings.apiGatewayBaseUrl)
        assertEquals(EndpointSelectionMode.AUTO, settings.endpointSelectionMode)
    }

    @Test
    fun `isLoopbackBaseUrl recognizes localhost variants`() {
        assertTrue(DesktopConfigResolver.isLoopbackBaseUrl("http://127.0.0.1:8080"))
        assertTrue(DesktopConfigResolver.isLoopbackBaseUrl("http://localhost:8080"))
        assertFalse(DesktopConfigResolver.isLoopbackBaseUrl("https://api.jarvis.local"))
        assertFalse(DesktopConfigResolver.isLoopbackBaseUrl(null))
    }
}
