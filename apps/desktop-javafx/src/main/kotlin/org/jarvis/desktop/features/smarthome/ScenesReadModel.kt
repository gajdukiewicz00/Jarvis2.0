package org.jarvis.desktop.features.smarthome

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Read model for the Smart Home "Scenes" panel.
 *
 * A scene is a named set of device actions applied together (e.g.
 * "movie-night" -> dim lights, lock door).
 *
 * Wires:
 *  - list scenes    -> GET    /api/v1/smarthome/scenes
 *  - create scene   -> POST   /api/v1/smarthome/scenes
 *  - delete scene   -> DELETE /api/v1/smarthome/scenes/{name}
 *  - simulate scene -> POST   /api/v1/smarthome/scenes/{name}/simulate?confirm=
 *  - activate scene -> POST   /api/v1/smarthome/scenes/{name}/activate?confirm=
 */
class ScenesReadModel(
    private val apiClient: ApiClient
) {
    private val objectMapper = jacksonObjectMapper()

    data class SceneStep(
        val deviceId: String,
        val action: String,
        val payload: String?
    )

    data class Scene(
        val name: String,
        val steps: List<SceneStep>
    )

    data class ActivationResult(
        val applied: Int,
        val summary: String
    )

    /** Mirrors `SmartHomeSimulatedAction` — a single predicted (never-actuated) step outcome. */
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

    /** Mirrors `SmartHomeSceneSimulation` — the dry-run outcome of activating a scene. */
    data class SceneSimulation(
        val sceneName: String,
        val found: Boolean,
        val actions: List<SimulatedAction>,
        val message: String?
    )

    fun loadScenes(): List<Scene> {
        val response = apiClient.get("/smarthome/scenes")
        val root = objectMapper.readTree(response)
        return if (root.isArray) root.map(::parseScene) else emptyList()
    }

    fun createScene(name: String, steps: List<SceneStep>): Scene {
        val payload = objectMapper.createObjectNode().apply {
            put("name", name.trim())
            putArray("steps").apply {
                steps.forEach { step ->
                    addObject().apply {
                        put("deviceId", step.deviceId)
                        put("action", step.action)
                        if (step.payload.isNullOrBlank()) putNull("payload") else put("payload", step.payload)
                    }
                }
            }
        }
        val response = apiClient.post("/smarthome/scenes", objectMapper.writeValueAsString(payload))
        return parseScene(objectMapper.readTree(response))
    }

    fun deleteScene(name: String) {
        apiClient.delete("/smarthome/scenes/${encode(name)}")
    }

    /** Applies every step via the device action pipeline; degrades per-step, never throws for step failures. */
    fun activateScene(name: String): ActivationResult {
        val emptyPayload = objectMapper.writeValueAsString(objectMapper.createObjectNode())
        val response = apiClient.post("/smarthome/scenes/${encode(name)}/activate", emptyPayload)
        val root = objectMapper.readTree(response)
        val applied = root.path("applied").asInt(0)
        val results = root.path("results").takeIf(JsonNode::isArray)
        val summary = results?.takeIf { it.size() > 0 }
            ?.joinToString("; ", transform = ::summarizeStepResult)
            ?: "no steps applied"
        return ActivationResult(applied, summary)
    }

    private fun summarizeStepResult(node: JsonNode): String {
        val error = node.path("error").takeIf { !it.isMissingNode && !it.isNull }?.asText()
        if (error != null) {
            val deviceId = node.path("deviceId").asText("")
            return "$deviceId failed: $error"
        }
        val deviceId = node.path("device").path("id").asText(node.path("deviceId").asText(""))
        val action = node.path("action").asText("")
        return if (deviceId.isBlank() && action.isBlank()) "step ok" else "$deviceId $action".trim()
    }

    private fun parseScene(node: JsonNode): Scene {
        val steps = node.path("steps").takeIf(JsonNode::isArray)?.map(::parseStep) ?: emptyList()
        return Scene(
            name = node.path("name").asText("Unnamed scene"),
            steps = steps
        )
    }

    private fun parseStep(node: JsonNode): SceneStep {
        return SceneStep(
            deviceId = node.path("deviceId").asText(""),
            action = node.path("action").asText(""),
            payload = node.path("payload").let { if (it.isMissingNode || it.isNull) null else it.asText() }
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
