package org.jarvis.desktop.service

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.UnknownHostException

class TransportErrorFormatterTest {

    @Test
    @DisplayName("null connect exception falls back to type and endpoint without literal null")
    fun nullConnectExceptionIsSanitized() {
        val description = TransportErrorFormatter.describeFailure(
            channel = "Voice WebSocket",
            endpoint = "ws://localhost:8080/ws/voice",
            throwable = ConnectException()
        )

        assertTrue(description.userMessage.contains("Could not connect", ignoreCase = true))
        assertTrue(description.userMessage.contains("ws://localhost:8080/ws/voice"))
        assertTrue(description.diagnosticMessage.contains("[ConnectException]"))
        assertFalse(description.userMessage.contains("null", ignoreCase = true))
        assertFalse(description.diagnosticMessage.contains("null", ignoreCase = true))
    }

    @Test
    @DisplayName("unknown host is classified explicitly")
    fun unknownHostIsTyped() {
        val description = TransportErrorFormatter.describeFailure(
            channel = "PC Control WebSocket",
            endpoint = "ws://desktop.invalid/ws/pc-control",
            throwable = UnknownHostException()
        )

        assertTrue(description.userMessage.contains("Host not found", ignoreCase = true))
        assertTrue(description.userMessage.contains("desktop.invalid"))
        assertTrue(description.diagnosticMessage.contains("[UnknownHostException]"))
    }

    @Test
    @DisplayName("http upgrade failure includes status and body in diagnostics")
    fun httpUpgradeFailureCapturesResponseDetails() {
        val response = Response.Builder()
            .request(Request.Builder().url("wss://api.jarvis.local/ws/voice").build())
            .protocol(Protocol.HTTP_1_1)
            .code(503)
            .message("Service Unavailable")
            .body("""{"error":"gateway down"}""".toResponseBody())
            .build()

        val description = TransportErrorFormatter.describeFailure(
            channel = "Voice WebSocket",
            endpoint = "wss://api.jarvis.local/ws/voice",
            throwable = IllegalStateException(),
            response = response
        )

        assertTrue(description.userMessage.contains("HTTP 503"))
        assertTrue(description.diagnosticMessage.contains("Service Unavailable"))
        assertTrue(description.diagnosticMessage.contains("gateway down"))
        assertFalse(description.diagnosticMessage.contains("null", ignoreCase = true))
    }
}
