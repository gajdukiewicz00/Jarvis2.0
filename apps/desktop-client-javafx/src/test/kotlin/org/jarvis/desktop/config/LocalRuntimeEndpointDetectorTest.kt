package org.jarvis.desktop.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class LocalRuntimeEndpointDetectorTest {

    @Test
    fun `detectActive probes readiness endpoint from last run summary`(@TempDir tempDir: Path) {
        val summaryPath = tempDir.resolve("last-run.json")
        Files.writeString(
            summaryPath,
            """
            {
              "runtimeMode": "local",
              "status": "ready",
              "apiUrl": "http://127.0.0.1:8080",
              "timestamp": "2026-03-28T12:56:36Z"
            }
            """.trimIndent()
        )

        val seenUris = mutableListOf<URI>()
        val detector = LocalRuntimeEndpointDetector(
            summaryPath = summaryPath,
            healthProbe = { uri ->
                seenUris += uri
                true
            },
            clock = Clock.fixed(Instant.parse("2026-03-28T12:57:06Z"), ZoneOffset.UTC)
        )

        val snapshot = detector.detectActive()

        assertNotNull(snapshot)
        assertEquals("http://127.0.0.1:8080", snapshot?.apiGatewayBaseUrl)
        assertEquals(RuntimeEndpointMode.LOCAL, snapshot?.runtimeMode)
        assertEquals(
            listOf(URI.create("http://127.0.0.1:8080/actuator/health/readiness")),
            seenUris
        )
        assertTrue(snapshot?.reason?.contains("actuator readiness probe OK") == true)
    }

    @Test
    fun `detectActive accepts k8s runtime summaries when ingress is healthy`(@TempDir tempDir: Path) {
        val summaryPath = tempDir.resolve("last-run.json")
        Files.writeString(
            summaryPath,
            """
            {
              "runtimeMode": "k8s",
              "status": "ready",
              "apiUrl": "https://api.jarvis.local",
              "timestamp": "2026-04-08T21:47:33Z"
            }
            """.trimIndent()
        )

        val seenUris = mutableListOf<URI>()
        val detector = LocalRuntimeEndpointDetector(
            summaryPath = summaryPath,
            healthProbe = { uri ->
                seenUris += uri
                true
            },
            clock = Clock.fixed(Instant.parse("2026-04-08T21:48:03Z"), ZoneOffset.UTC)
        )

        val snapshot = detector.detectActive()

        assertNotNull(snapshot)
        assertEquals("https://api.jarvis.local", snapshot?.apiGatewayBaseUrl)
        assertEquals(RuntimeEndpointMode.K8S, snapshot?.runtimeMode)
        assertEquals(
            listOf(URI.create("https://api.jarvis.local/actuator/health/readiness")),
            seenUris
        )
        assertTrue(snapshot?.reason?.contains("Active k8s runtime detected") == true)
    }
}
