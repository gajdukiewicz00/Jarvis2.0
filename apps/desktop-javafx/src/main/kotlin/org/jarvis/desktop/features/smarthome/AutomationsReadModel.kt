package org.jarvis.desktop.features.smarthome

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Read model for the Smart Home "Automations" panel — trigger -> action rules
 * evaluated whenever a matching sensor reading is ingested (e.g. motion on
 * `hall_motion` turns on `hall_light`).
 *
 * Wires:
 *  - list rules -> GET  /api/v1/smarthome/automation/rules
 *  - simulate   -> POST /api/v1/smarthome/devices/{deviceId}/automation/simulate
 *
 * [simulate] is a dry run: it evaluates every rule against the device's
 * latest known sensor reading(s) and reports what WOULD happen, mirroring
 * `SmartHomeAutomationEngine#simulate` — it never calls
 * `SmartHomeService#executeAction`, so it is safe to call at any time.
 */
class AutomationsReadModel(
    private val apiClient: ApiClient
) {
    private val objectMapper = jacksonObjectMapper()

    data class AutomationRule(
        val id: String,
        val name: String,
        val triggerDeviceId: String,
        val triggerEvent: String,
        val triggerThreshold: Double?,
        val actionDeviceId: String,
        val actionType: String,
        val actionPayload: String?,
        val allowSensitiveActions: Boolean,
        val enabled: Boolean
    )

    /** Mirrors `SmartHomeSimulatedAction` — a single predicted (never-actuated) device action. */
    data class SimulatedAction(
        val deviceId: String,
        val action: String,
        val payload: String?,
        val deviceFound: Boolean,
        val actionSupported: Boolean,
        val needsConfirmation: Boolean,
        val wouldExecute: Boolean,
        val message: String?
    )

    /** Mirrors `SmartHomeAutomationSimulation` — one rule's dry-run outcome for a reading. */
    data class RuleSimulation(
        val ruleId: String,
        val ruleName: String,
        val triggered: Boolean,
        val predictedAction: SimulatedAction?,
        val message: String?
    )

    fun loadRules(): List<AutomationRule> {
        val response = apiClient.get("/smarthome/automation/rules")
        val root = objectMapper.readTree(response)
        return if (root.isArray) root.map(::parseRule) else emptyList()
    }

    /**
     * Dry-run simulate every enabled rule whose trigger device is [deviceId],
     * against that device's latest known sensor reading(s). Only rules whose
     * trigger condition currently matches are returned — mirrors the backend's
     * "no readings supplied -> use latest known" fallback.
     */
    fun simulate(deviceId: String): List<RuleSimulation> {
        val emptyPayload = objectMapper.writeValueAsString(objectMapper.createObjectNode())
        val response = apiClient.post("/smarthome/devices/${encode(deviceId)}/automation/simulate", emptyPayload)
        val root = objectMapper.readTree(response)
        return if (root.isArray) root.map(::parseSimulation) else emptyList()
    }

    private fun parseRule(node: JsonNode): AutomationRule {
        return AutomationRule(
            id = node.path("id").textOrNull() ?: "",
            name = node.path("name").textOrNull() ?: "",
            triggerDeviceId = node.path("triggerDeviceId").textOrNull() ?: "",
            triggerEvent = node.path("triggerEvent").textOrNull() ?: "",
            triggerThreshold = node.path("triggerThreshold").let { if (it.isNumber) it.asDouble() else null },
            actionDeviceId = node.path("actionDeviceId").textOrNull() ?: "",
            actionType = node.path("actionType").textOrNull() ?: "",
            actionPayload = node.path("actionPayload").textOrNull(),
            allowSensitiveActions = node.path("allowSensitiveActions").let { it.isBoolean && it.asBoolean() },
            enabled = node.path("enabled").let { it.isBoolean && it.asBoolean() }
        )
    }

    private fun parseSimulation(node: JsonNode): RuleSimulation {
        return RuleSimulation(
            ruleId = node.path("ruleId").textOrNull() ?: "",
            ruleName = node.path("ruleName").textOrNull() ?: "",
            triggered = node.path("triggered").let { it.isBoolean && it.asBoolean() },
            predictedAction = node.path("predictedAction").takeIf { !it.isMissingNode && !it.isNull }
                ?.let(::parseSimulatedAction),
            message = node.path("message").textOrNull()
        )
    }

    private fun parseSimulatedAction(node: JsonNode): SimulatedAction {
        return SimulatedAction(
            deviceId = node.path("deviceId").textOrNull() ?: "",
            action = node.path("action").textOrNull() ?: "",
            payload = node.path("payload").textOrNull(),
            deviceFound = node.path("deviceFound").let { it.isBoolean && it.asBoolean() },
            actionSupported = node.path("actionSupported").let { it.isBoolean && it.asBoolean() },
            needsConfirmation = node.path("needsConfirmation").let { it.isBoolean && it.asBoolean() },
            wouldExecute = node.path("wouldExecute").let { it.isBoolean && it.asBoolean() },
            message = node.path("message").textOrNull()
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun JsonNode.textOrNull(): String? =
        if (isMissingNode || isNull) null else asText(null)?.takeIf { it.isNotBlank() }
}
