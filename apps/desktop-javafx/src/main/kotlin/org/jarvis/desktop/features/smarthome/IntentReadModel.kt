package org.jarvis.desktop.features.smarthome

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient

/**
 * Read model for the natural-language smart-home intent box.
 *
 * Wires: `POST /api/v1/smarthome/intent` (`{"utterance": "..."}`).
 *
 * This only plans the command — mirroring `SmartHomeController#resolveIntent`,
 * nothing is ever actuated here. Executing the resolved plan is a separate,
 * explicit call through [SmartHomeActionReadModel].
 */
class IntentReadModel(
    private val apiClient: ApiClient
) {
    private val objectMapper = jacksonObjectMapper()

    data class DeviceRef(
        val id: String,
        val displayName: String,
        val room: String,
        val supportedActions: List<String>
    )

    data class Resolution(
        val utterance: String,
        val status: String,
        val confidence: Double,
        val action: String?,
        val payload: String?,
        val device: DeviceRef?,
        val candidates: List<DeviceRef>,
        val message: String?
    ) {
        /** Mirrors `IntentMatchStatus.RESOLVED` — a device + action plan is ready to execute. */
        val isExecutable: Boolean
            get() = status == "RESOLVED" && device != null && !action.isNullOrBlank()
    }

    fun resolve(utterance: String): Resolution {
        val payload = objectMapper.createObjectNode().apply { put("utterance", utterance.trim()) }
        val root = objectMapper.readTree(apiClient.post("/smarthome/intent", objectMapper.writeValueAsString(payload)))
        return Resolution(
            utterance = root.path("utterance").textOrNull() ?: utterance,
            status = root.path("status").textOrNull() ?: "UNKNOWN",
            confidence = root.path("confidence").let { if (it.isNumber) it.asDouble() else 0.0 },
            action = root.path("action").textOrNull(),
            payload = root.path("payload").textOrNull(),
            device = root.path("device").takeIf { !it.isMissingNode && !it.isNull }?.let(::parseDevice),
            candidates = root.path("candidates").takeIf(JsonNode::isArray)?.map(::parseDevice) ?: emptyList(),
            message = root.path("message").textOrNull()
        )
    }

    private fun parseDevice(node: JsonNode): DeviceRef {
        return DeviceRef(
            id = node.path("id").textOrNull() ?: "",
            displayName = node.path("displayName").textOrNull() ?: "",
            room = node.path("room").textOrNull() ?: "",
            supportedActions = node.path("supportedActions").takeIf(JsonNode::isArray)
                ?.mapNotNull { it.textOrNull() } ?: emptyList()
        )
    }

    private fun JsonNode.textOrNull(): String? =
        if (isMissingNode || isNull) null else asText(null)?.takeIf { it.isNotBlank() }
}
