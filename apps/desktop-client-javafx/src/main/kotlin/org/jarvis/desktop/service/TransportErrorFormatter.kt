package org.jarvis.desktop.service

import okhttp3.Response
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import javax.net.ssl.SSLException

object TransportErrorFormatter {

    data class Description(
        val type: String,
        val userMessage: String,
        val diagnosticMessage: String
    )

    fun describeFailure(
        channel: String,
        endpoint: String,
        throwable: Throwable,
        response: Response? = null
    ): Description {
        val chain = generateSequence(throwable) { it.cause }.toList()
        val type = chain.firstNotNullOfOrNull { errorType(it) } ?: "UnknownError"
        val detail = firstNonBlankMessage(chain)
        val statusCode = response?.code
        val statusMessage = response?.message?.trim()?.takeIf(String::isNotBlank)
        val responseBody = responseBodySnippet(response)

        return when {
            chain.any { it is UnknownHostException } -> description(
                type = type,
                userMessage = "Host not found for $channel at $endpoint.",
                diagnosticMessage = buildDiagnostic(
                    channel = channel,
                    type = type,
                    endpoint = endpoint,
                    summary = detail ?: "DNS lookup failed"
                )
            )

            chain.any { it is SSLException } -> description(
                type = type,
                userMessage = "TLS error while connecting to $channel at $endpoint.",
                diagnosticMessage = buildDiagnostic(
                    channel = channel,
                    type = type,
                    endpoint = endpoint,
                    summary = detail ?: "TLS handshake failed"
                )
            )

            chain.any { it is SocketTimeoutException || it is HttpTimeoutException } ||
                chain.any { it is InterruptedIOException && messageLooksLikeTimeout(it.message) } -> description(
                type = type,
                userMessage = "Connection to $channel timed out at $endpoint.",
                diagnosticMessage = buildDiagnostic(
                    channel = channel,
                    type = type,
                    endpoint = endpoint,
                    summary = detail ?: "connection timed out"
                )
            )

            chain.any { it is ConnectException } || detailLooksLikeConnectFailure(detail) -> description(
                type = type,
                userMessage = "Could not connect to $channel at $endpoint.",
                diagnosticMessage = buildDiagnostic(
                    channel = channel,
                    type = type,
                    endpoint = endpoint,
                    summary = detail ?: "connection refused or endpoint unavailable"
                )
            )

            statusCode != null -> description(
                type = type,
                userMessage = "$channel handshake failed with HTTP $statusCode at $endpoint.",
                diagnosticMessage = buildDiagnostic(
                    channel = channel,
                    type = type,
                    endpoint = endpoint,
                    summary = listOfNotNull(
                        "HTTP $statusCode",
                        statusMessage,
                        responseBody?.let { "body=$it" }
                    ).joinToString(", ")
                )
            )

            detail != null -> description(
                type = type,
                userMessage = "$channel transport failed at $endpoint: $detail.",
                diagnosticMessage = buildDiagnostic(
                    channel = channel,
                    type = type,
                    endpoint = endpoint,
                    summary = detail
                )
            )

            else -> description(
                type = type,
                userMessage = "$channel transport failed at $endpoint ($type).",
                diagnosticMessage = buildDiagnostic(
                    channel = channel,
                    type = type,
                    endpoint = endpoint,
                    summary = "no additional detail"
                )
            )
        }
    }

    fun describeClose(channel: String, endpoint: String, code: Int, reason: String?): Description {
        val normalizedReason = reason?.trim()?.takeIf(String::isNotBlank)
        val closeSummary = when (code) {
            1000 -> "normal closure"
            1001 -> "server is going away"
            1006 -> "connection lost unexpectedly"
            1011 -> "server reported an internal error"
            else -> "closed by remote peer"
        }
        val reasonSuffix = normalizedReason?.let { ": $it" }.orEmpty()
        val userMessage = when (code) {
            1000 -> "$channel disconnected."
            else -> "Connection to $channel was closed unexpectedly (code $code)$reasonSuffix"
        }
        return description(
            type = "WebSocketClose",
            userMessage = userMessage,
            diagnosticMessage = buildDiagnostic(
                channel = channel,
                type = "WebSocketClose",
                endpoint = endpoint,
                summary = "code=$code, reason=${normalizedReason ?: closeSummary}"
            )
        )
    }

    private fun description(type: String, userMessage: String, diagnosticMessage: String): Description {
        return Description(
            type = type,
            userMessage = sanitizeSentence(userMessage),
            diagnosticMessage = sanitizeSentence(diagnosticMessage)
        )
    }

    private fun buildDiagnostic(channel: String, type: String, endpoint: String, summary: String): String {
        return "$channel transport failure [$type] endpoint=$endpoint: $summary"
    }

    private fun errorType(throwable: Throwable): String? {
        return throwable::class.java.simpleName?.takeIf(String::isNotBlank)
    }

    private fun firstNonBlankMessage(chain: List<Throwable>): String? {
        return chain.asSequence()
            .mapNotNull { it.message?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
    }

    private fun responseBodySnippet(response: Response?): String? {
        if (response == null) {
            return null
        }
        return runCatching { response.peekBody(256).string() }
            .getOrNull()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.take(160)
    }

    private fun detailLooksLikeConnectFailure(detail: String?): Boolean {
        val normalized = detail?.lowercase() ?: return false
        return "connection refused" in normalized ||
            "failed to connect" in normalized ||
            "connect exception" in normalized
    }

    private fun messageLooksLikeTimeout(message: String?): Boolean {
        val normalized = message?.lowercase() ?: return false
        return "timeout" in normalized || "timed out" in normalized
    }

    private fun sanitizeSentence(text: String): String {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) {
            return "Transport failure."
        }
        return if (normalized.endsWith(".")) normalized else "$normalized."
    }
}
