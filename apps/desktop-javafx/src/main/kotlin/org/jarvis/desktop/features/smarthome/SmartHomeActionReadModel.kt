package org.jarvis.desktop.features.smarthome

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Read model for executing a single device action, shared by the plain
 * device list ([org.jarvis.desktop.ui.tabs.DevicesTab]) and the natural-language
 * intent panel ([IntentView]).
 *
 * Wires: `POST /api/v1/smarthome/devices/{deviceId}/action?confirm=`
 *
 * Security-critical device types (locks, doors, garages) are rejected by the
 * backend's `SafetyPolicy` unless [confirm] is `true` — the response then
 * carries `success=false, needsConfirmation=true` instead of a 4xx, so a
 * caller always sends the unconfirmed request first and only resubmits with
 * `confirm=true` after the owner explicitly confirms.
 */
class SmartHomeActionReadModel(
    private val apiClient: ApiClient
) {
    private val objectMapper = jacksonObjectMapper()

    data class ActionOutcome(
        val success: Boolean,
        val needsConfirmation: Boolean,
        val action: String,
        val message: String?
    )

    fun execute(deviceId: String, action: String, payload: String?, confirm: Boolean = false): ActionOutcome {
        val body = objectMapper.createObjectNode().apply {
            put("action", action)
            if (payload.isNullOrBlank()) putNull("payload") else put("payload", payload)
        }
        val query = "/smarthome/devices/${encode(deviceId)}/action?confirm=$confirm"
        val root = objectMapper.readTree(apiClient.post(query, objectMapper.writeValueAsString(body)))
        return ActionOutcome(
            success = root.path("success").let { it.isBoolean && it.asBoolean() },
            needsConfirmation = root.path("needsConfirmation").let { it.isBoolean && it.asBoolean() },
            action = root.path("action").textOrNull() ?: action,
            message = root.path("message").textOrNull()
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun JsonNode.textOrNull(): String? =
        if (isMissingNode || isNull) null else asText(null)?.takeIf { it.isNotBlank() }
}
