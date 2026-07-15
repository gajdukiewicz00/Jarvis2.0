package org.jarvis.desktop.service.wake

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Regression coverage for [OkHttpWakeSidecarClient] parsing against the REAL sidecar
 * JSON shapes — /devices entries and /diagnostics selectedDevice are OBJECTS
 * ({id,name,...}), not bare strings. A prior version called .jsonPrimitive on the
 * object and returned an empty device list + null diagnostics; this pins the fix.
 */
class OkHttpWakeSidecarClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpWakeSidecarClient

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        client = OkHttpWakeSidecarClient(server.url("/").toString().trimEnd('/'))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `devices parses object entries and returns their names`() {
        server.enqueue(
            MockResponse().setBody(
                """{"devices":[
                    {"id":2,"name":"Creative Live! Cam Sync 4K: USB Audio (hw:1,0)","isInput":true,"preferred":true},
                    {"id":7,"name":"T1: USB Audio (hw:3,0)","isInput":true,"preferred":true},
                    {"id":10,"name":"default","isInput":true,"preferred":false}
                ]}"""
            ).addHeader("Content-Type", "application/json")
        )

        val devices = client.devices()

        assertEquals(3, devices.size)
        assertTrue(devices.any { it.contains("C4K", true) || it.contains("Creative", true) })
        assertTrue(devices.any { it.startsWith("T1") })
    }

    @Test
    fun `diagnostics parses selectedDevice OBJECT and does not null the whole payload`() {
        server.enqueue(
            MockResponse().setBody(
                """{"provider":"openWakeWord","installed":true,"engine":"openwakeword",
                    "models":["hey_jarvis_v0.1"],
                    "selectedDevice":{"id":2,"name":"Creative Live! Cam Sync 4K: USB Audio (hw:1,0)","isInput":true},
                    "listening":true,"lastWakeDetectedAt":"2026-07-15T18:41:18","lastWakeScore":0.83,"lastError":null}"""
            ).addHeader("Content-Type", "application/json")
        )

        val diag = client.diagnostics()

        assertNotNull(diag)
        assertTrue(diag!!.listening)
        assertTrue(diag.selectedDevice!!.contains("Creative", true))
        assertEquals(listOf("hey_jarvis_v0.1"), diag.models)
        assertEquals(0.83, diag.lastWakeScore)
        assertEquals(true, diag.installed)
    }

    @Test
    fun `health parses UP`() {
        server.enqueue(
            MockResponse().setBody("""{"status":"UP","provider":"openWakeWord","listening":true}""")
        )
        assertTrue(client.health().up)
    }

    @Test
    fun `startEngine success returns started`() {
        server.enqueue(MockResponse().setBody("""{"status":"STARTED","engine":"openwakeword"}"""))
        val r = client.startEngine(StartEngineRequest("auto", "hey_jarvis", 0.5, "openwakeword"))
        assertTrue(r.started)
        assertEquals(200, r.statusCode)
    }

    @Test
    fun `startEngine vosk-not-installed surfaces the error and not-started`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"vosk_not_installed"}"""))
        val r = client.startEngine(StartEngineRequest("auto", "hey_jarvis", 0.5, "vosk"))
        assertFalse(r.started)
        assertEquals(503, r.statusCode)
        assertEquals("vosk_not_installed", r.error)
    }

    @Test
    fun `pause posts to slash pause and returns success`() {
        server.enqueue(MockResponse().setBody("""{"status":"PAUSED"}"""))

        assertTrue(client.pause())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/pause", recorded.path)
    }

    @Test
    fun `resume posts to slash resume and returns success`() {
        server.enqueue(MockResponse().setBody("""{"status":"LISTENING"}"""))

        assertTrue(client.resume())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/resume", recorded.path)
    }

    @Test
    fun `pause returns false on a non-2xx response and never throws`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        assertFalse(client.pause())
    }

    @Test
    fun `diagnostics parses the paused flag when present`() {
        server.enqueue(
            MockResponse().setBody(
                """{"installed":true,"models":["hey_jarvis"],"listening":true,"paused":true}"""
            ).addHeader("Content-Type", "application/json")
        )

        val diag = client.diagnostics()

        assertNotNull(diag)
        assertEquals(true, diag!!.paused)
    }
}
