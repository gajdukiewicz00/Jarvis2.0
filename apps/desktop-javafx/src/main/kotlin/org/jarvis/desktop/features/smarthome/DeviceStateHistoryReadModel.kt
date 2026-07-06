package org.jarvis.desktop.features.smarthome

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Read model for the Smart Home device "State history" panel — a bounded,
 * most-recent-first audit trail of state changes for one device.
 *
 * Wires: `GET /api/v1/smarthome/devices/{deviceId}/state-history?limit=`
 *
 * Scoped server-side to the authenticated caller (via the gateway-injected
 * `X-User-Id`) — one owner never sees another owner's device history.
 */
class DeviceStateHistoryReadModel(
    private val apiClient: ApiClient
) {
    companion object {
        const val DEFAULT_LIMIT = 50
    }

    private val objectMapper = jacksonObjectMapper()

    /** Mirrors `DeviceStateHistoryEntry` — one persisted state snapshot after a successful action. */
    data class HistoryEntry(
        val deviceId: String,
        val action: String,
        val payload: String?,
        val stateJson: String,
        val success: Boolean,
        val recordedAt: String?
    )

    fun history(deviceId: String, limit: Int = DEFAULT_LIMIT): List<HistoryEntry> {
        val response = apiClient.get("/smarthome/devices/${encode(deviceId)}/state-history?limit=$limit")
        val root = objectMapper.readTree(response)
        return if (root.isArray) root.map(::parseEntry) else emptyList()
    }

    private fun parseEntry(node: JsonNode): HistoryEntry {
        return HistoryEntry(
            deviceId = node.path("deviceId").textOrNull() ?: "",
            action = node.path("action").textOrNull() ?: "",
            payload = node.path("payload").textOrNull(),
            stateJson = node.path("stateJson").textOrNull() ?: "{}",
            success = node.path("success").let { it.isBoolean && it.asBoolean() },
            recordedAt = node.path("recordedAt").textOrNull()
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun JsonNode.textOrNull(): String? =
        if (isMissingNode || isNull) null else asText(null)?.takeIf { it.isNotBlank() }
}
