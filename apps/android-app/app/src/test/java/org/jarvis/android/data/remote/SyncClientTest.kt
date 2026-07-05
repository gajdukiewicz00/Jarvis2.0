package org.jarvis.android.data.remote

import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Exercises [SyncClient]'s real network code paths — including the success and
 * error-response branches of `pairingInit` / `pairingComplete` / `postBlob` — against a
 * throwaway raw-socket HTTP/1.1 stub instead of the real sync-service.
 *
 * A hand-rolled [java.net.ServerSocket] stub is used (rather than
 * `com.sun.net.httpserver.HttpServer` or an OkHttp MockWebServer dependency) because this
 * Android module compiles JVM unit tests against a release-restricted JDK API surface that
 * excludes `jdk.httpserver`, and adding a new test dependency was avoided to keep this
 * change confined to test sources. `java.net.ServerSocket` is part of `java.se` and is
 * always available.
 */
class SyncClientTest {

    private lateinit var server: SingleResponseServer
    private val json = Json { ignoreUnknownKeys = true }

    @After
    fun stopServer() {
        if (::server.isInitialized) server.stop()
    }

    private fun serverReturning(statusCode: Int, body: String): String {
        server = SingleResponseServer(statusCode, body).apply { start() }
        return "http://127.0.0.1:${server.port}"
    }

    @Test
    fun pairingInit_parsesSuccessfulResponse() {
        val expected = SyncClient.PairingInitResponse(
            pairingNonceB64 = "nonce-abc",
            serverKexPubB64 = "kex-xyz"
        )
        val baseUrl = serverReturning(200, json.encodeToString(SyncClient.PairingInitResponse.serializer(), expected))

        val result = SyncClient(baseUrl).pairingInit()

        assertEquals(expected, result)
    }

    @Test
    fun pairingInit_throwsWhenServerRejects() {
        val baseUrl = serverReturning(500, "{}")

        val error = assertThrows(IllegalStateException::class.java) {
            SyncClient(baseUrl).pairingInit()
        }
        assertTrue(error.message!!.contains("pairing init failed"))
    }

    @Test
    fun pairingComplete_sendsRequestBodyAndParsesResponse() {
        val expectedResponse = SyncClient.PairingResponse(
            serverKexPubB64 = "server-kex",
            routingId = "routing-9",
            senderDeviceId = "device-9",
            pairedAt = "2026-07-05T00:00:00Z"
        )
        val baseUrl = serverReturning(200, json.encodeToString(SyncClient.PairingResponse.serializer(), expectedResponse))

        val request = SyncClient.PairingRequest(
            deviceLabel = "pixel-9",
            identityPubB64 = "id-pub",
            kexPubB64 = "kex-pub",
            pairingNonceB64 = "nonce",
            identitySigB64 = "sig"
        )
        val result = SyncClient(baseUrl).pairingComplete(request)

        assertEquals(expectedResponse, result)
        assertEquals(request, json.decodeFromString(SyncClient.PairingRequest.serializer(), server.capturedRequestBody))
    }

    @Test
    fun pairingComplete_throwsWithServerErrorBodyOnFailure() {
        val baseUrl = serverReturning(401, "{\"error\":\"identity_signature_invalid\"}")

        val request = SyncClient.PairingRequest("label", "id", "kex", "nonce", "sig")
        val error = assertThrows(IllegalStateException::class.java) {
            SyncClient(baseUrl).pairingComplete(request)
        }
        assertTrue(error.message!!.contains("identity_signature_invalid"))
    }

    @Test
    fun postBlob_returnsHttpStatusCodeOnSuccess() {
        val baseUrl = serverReturning(202, "{}")

        val envelope = SyncClient.SyncEnvelope(
            version = 1,
            routingId = "r",
            senderDeviceId = "d",
            nonceB64 = "n",
            ciphertextB64 = "c",
            occurredAtClient = "2026-07-05T00:00:00Z"
        )
        val code = SyncClient(baseUrl).postBlob(envelope)

        assertEquals(202, code)
    }

    @Test
    fun postBlob_returnsErrorStatusCodeWithoutThrowing() {
        val baseUrl = serverReturning(503, "{}")

        val envelope = SyncClient.SyncEnvelope(1, "r", "d", "n", "c", "2026-07-05T00:00:00Z")
        val code = SyncClient(baseUrl).postBlob(envelope)

        assertEquals(503, code)
    }
}

/**
 * Minimal single-request HTTP/1.1 stub: accepts exactly one connection, reads the request
 * line/headers/body (to capture it for assertions), then writes back a fixed status + body.
 * Good enough to drive OkHttp's client code paths without a real server or extra test deps.
 */
private class SingleResponseServer(private val statusCode: Int, private val responseBody: String) {
    private val serverSocket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = serverSocket.localPort

    @Volatile
    var capturedRequestBody: String = ""
        private set

    private val thread = Thread {
        try {
            serverSocket.accept().use { socket -> handle(socket) }
        } catch (_: Exception) {
            // Expected once stop() closes the server socket while accept() is blocked.
        }
    }.apply { isDaemon = true }

    fun start() = thread.start()

    fun stop() {
        runCatching { serverSocket.close() }
        thread.join(2_000)
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        readLine(input) // request line, e.g. "POST /api/v1/sync/pairing/init HTTP/1.1" — path irrelevant here
        var contentLength = 0
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val (name, value) = line.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (name.trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = value.trim().toIntOrNull() ?: 0
            }
        }
        val bodyBytes = ByteArray(contentLength)
        var readTotal = 0
        while (readTotal < contentLength) {
            val n = input.read(bodyBytes, readTotal, contentLength - readTotal)
            if (n == -1) break
            readTotal += n
        }
        capturedRequestBody = String(bodyBytes, StandardCharsets.UTF_8)

        val responseBytes = responseBody.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $statusCode Status\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${responseBytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(responseBytes)
        output.flush()
    }

    private fun readLine(input: InputStream): String {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) bytes.add(b.toByte())
        }
        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }
}
