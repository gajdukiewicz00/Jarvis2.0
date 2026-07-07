package org.jarvis.desktop.features.media

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [MediaRawHttp] exists solely to correctly handle two things the shared
 * `ApiClient` cannot: a `202 Accepted` success response, and raw (non-UTF-8)
 * response bytes. These tests pin down exactly those two guarantees.
 */
class MediaRawHttpTest {

    private fun clientFor(server: MockWebServer) =
        MediaRawHttp { server.url("/").toString().removeSuffix("/") + "/api/v1" }

    @Test
    fun `postJson treats 202 Accepted as success and returns the body`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"job-1","status":"CREATED"}""")
        )

        try {
            server.start()
            val body = clientFor(server).postJson("/media/jobs/mux", """{"originalFile":"a.mp4"}""")

            assertTrue(body.contains("job-1"))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/media/jobs/mux", request.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `postJson throws MediaHttpException carrying the status code on a 4xx response`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad request"}"""))

        try {
            server.start()
            val ex = assertThrows(MediaRawHttp.MediaHttpException::class.java) {
                clientFor(server).postJson("/media/jobs/mux", "{}")
            }
            assertEquals(400, ex.statusCode)
            assertTrue(ex.message!!.contains("bad request"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `postJson gives a clear message on a 401 without attempting a token refresh`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))

        try {
            server.start()
            val ex = assertThrows(MediaRawHttp.MediaHttpException::class.java) {
                clientFor(server).postJson("/media/jobs/mux", "{}")
            }
            assertEquals(401, ex.statusCode)
            assertTrue(ex.message!!.contains("expired", ignoreCase = true))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `getBytes returns the exact response bytes unmodified, including non-UTF-8 bytes`() {
        val server = MockWebServer()
        val binary = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0x10, 0x7F, 0x80.toByte(), 0xC3.toByte())
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(binary))
        )

        try {
            server.start()
            val bytes = clientFor(server).getBytes("/media/jobs/job-1/artifacts/0")

            assertArrayEquals(binary, bytes)
            assertEquals("/api/v1/media/jobs/job-1/artifacts/0", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `getBytes throws MediaHttpException on a 404`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))

        try {
            server.start()
            val ex = assertThrows(MediaRawHttp.MediaHttpException::class.java) {
                clientFor(server).getBytes("/media/jobs/job-1/artifacts/9")
            }
            assertEquals(404, ex.statusCode)
        } finally {
            server.shutdown()
        }
    }
}
