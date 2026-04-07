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
              "apiUrl": "https://127.0.0.1:18080",
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
        assertEquals("https://127.0.0.1:18080", snapshot?.apiGatewayBaseUrl)
        assertEquals(
            listOf(URI.create("https://127.0.0.1:18080/actuator/health/readiness")),
            seenUris
        )
        assertTrue(snapshot?.reason?.contains("actuator readiness probe OK") == true)
    }
}
