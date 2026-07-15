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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    @Test
    fun `diagnostics parses the new live observability fields`() {
        server.enqueue(
            MockResponse().setBody(
                """{"installed":true,"models":["hey_jarvis_v0.1"],"listening":true,"ready":true,
                    "currentRms":0.042,"audioSignalPresent":true,"audioFramesReceived":15321,
                    "currentScore":0.31,"maximumScoreLast30Seconds":0.87,"inferenceCount":2048,
                    "threshold":0.5,"modelName":"hey_jarvis_v0.1","expectedWakePhrase":"hey jarvis",
                    "sampleRate":16000,"channels":1,"pcmFormat":"S16_LE"}"""
            ).addHeader("Content-Type", "application/json")
        )

        val diag = client.diagnostics()

        assertNotNull(diag)
        assertEquals(true, diag!!.ready)
        assertEquals(true, diag.audioSignalPresent)
        assertEquals(0.042, diag.currentRms)
        assertEquals(15321L, diag.audioFramesReceived)
        assertEquals(0.31, diag.currentScore)
        assertEquals(0.87, diag.maximumScoreLast30Seconds)
        assertEquals(2048L, diag.inferenceCount)
        assertEquals(0.5, diag.threshold)
        assertEquals("hey_jarvis_v0.1", diag.modelName)
        assertEquals("hey jarvis", diag.expectedWakePhrase)
        assertEquals(16000, diag.sampleRate)
        assertEquals(1, diag.channels)
        assertEquals("S16_LE", diag.pcmFormat)
    }

    @Test
    fun `diagnostics defaults the new fields to null when the sidecar omits them`() {
        server.enqueue(
            MockResponse().setBody(
                """{"installed":true,"models":["hey_jarvis"],"listening":true}"""
            ).addHeader("Content-Type", "application/json")
        )

        val diag = client.diagnostics()

        assertNotNull(diag)
        assertEquals(null, diag!!.audioSignalPresent)
        assertEquals(null, diag.currentScore)
        assertEquals(null, diag.expectedWakePhrase)
        assertEquals(null, diag.ready)
    }

    @Test
    fun `self-test parses a passing staged result and posts to slash self-test`() {
        server.enqueue(
            MockResponse().setBody(
                """{"stage":"detected","ok":true,"maxScore":0.998,"threshold":0.5,
                    "message":"Wake phrase detected."}"""
            ).addHeader("Content-Type", "application/json")
        )

        val result = client.selfTest()

        assertTrue(result.ok)
        assertEquals("detected", result.stage)
        assertEquals(0.998, result.maxScore)
        assertEquals(0.5, result.threshold)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/self-test", recorded.path)
    }

    @Test
    fun `self-test parses a failing staged result without faking success`() {
        server.enqueue(
            MockResponse().setBody(
                """{"stage":"below_threshold","ok":false,"maxScore":0.21,"threshold":0.5,
                    "message":"Audio signal present but model scores stayed below threshold (max 0.21)."}"""
            ).addHeader("Content-Type", "application/json")
        )

        val result = client.selfTest()

        assertFalse(result.ok)
        assertEquals("below_threshold", result.stage)
        assertEquals(0.21, result.maxScore)
        assertTrue(result.message.contains("below threshold"))
    }

    @Test
    fun `self-test never throws and returns a failure result when the sidecar is unreachable`() {
        // Nothing enqueued at a dead port → transport error must map to a failure result.
        val dead = OkHttpWakeSidecarClient("http://127.0.0.1:1")
        val result = dead.selfTest()
        assertFalse(result.ok)
        assertEquals("transport", result.stage)
    }

    @Test
    fun `calibrate parses the RMS summary and posts seconds to slash calibrate`() {
        server.enqueue(
            MockResponse().setBody(
                """{"device":"C4K","frameCount":94,"minRms":0.0,"avgRms":0.031,"maxRms":0.12,
                    "signalDetected":true}"""
            ).addHeader("Content-Type", "application/json")
        )

        val result = client.calibrate(3)

        assertNotNull(result)
        assertEquals("C4K", result!!.device)
        assertEquals(94L, result.frameCount)
        assertEquals(0.0, result.minRms)
        assertEquals(0.031, result.avgRms)
        assertEquals(0.12, result.maxRms)
        assertTrue(result.signalDetected)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/calibrate", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"seconds\":3"))
    }

    @Test
    fun `calibrate reports a dead mic with signalDetected false`() {
        server.enqueue(
            MockResponse().setBody(
                """{"device":"C4K","frameCount":94,"minRms":0.0,"avgRms":0.0,"maxRms":0.0,
                    "signalDetected":false}"""
            ).addHeader("Content-Type", "application/json")
        )

        val result = client.calibrate(3)

        assertNotNull(result)
        assertFalse(result!!.signalDetected)
        assertEquals(0.0, result.maxRms)
    }

    @Test
    fun `calibrate returns null on a non-2xx response and never throws`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        assertEquals(null, client.calibrate(3))
    }

    @Test
    fun `openEvents surfaces a data payload then close terminates the reader thread`() {
        val dataLatch = CountDownLatch(1)
        val received = CopyOnWriteArrayList<String>()

        // One real SSE data line, then a long trickle of NON-data comment lines to hold the
        // stream open (openEvents only surfaces lines starting with "data:"). throttleBody keeps
        // the socket open until we close() — deterministic, no fixed sleeps.
        val body = buildString {
            append("data: {\"type\":\"WAKE_DETECTED\",\"model\":\"hey_jarvis\",\"score\":0.91}\n")
            repeat(2000) { append(": keep-alive\n") }
        }
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "text/event-stream")
                .setBody(body)
                .throttleBody(120, 300, TimeUnit.MILLISECONDS)
        )

        val closeable = client.openEvents(
            onEvent = { received += it; dataLatch.countDown() },
            onError = { }
        )

        // The WAKE_DETECTED data line must surface promptly through onEvent.
        assertTrue(dataLatch.await(2, TimeUnit.SECONDS), "onEvent should fire for the data line")
        assertTrue(received.first().contains("WAKE_DETECTED"))

        // Closing must cancel the streaming call and let the daemon reader thread finish.
        closeable.close()
        assertTrue(awaitReaderThreadGone(2_000L), "SSE reader thread should terminate after close()")
    }

    /** Poll (bounded) until no live thread with the SSE reader's name remains. */
    private fun awaitReaderThreadGone(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (readerThreadAbsent()) return true
            Thread.sleep(25)
        }
        return readerThreadAbsent()
    }

    private fun readerThreadAbsent(): Boolean =
        Thread.getAllStackTraces().keys.none { it.isAlive && it.name == SSE_READER_THREAD_NAME }

    private companion object {
        const val SSE_READER_THREAD_NAME = "jarvis-wake-sse-reader"
    }
}
