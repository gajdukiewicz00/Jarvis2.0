package org.jarvis.desktop.features.vision

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient
import java.time.Instant

class VisionSecurityReadModel(
    private val apiClient: ApiClient
) {
    private val objectMapper = jacksonObjectMapper()

    data class Snapshot(
        val refreshedAt: Instant,
        val status: StatusSnapshot,
        val incidents: List<IncidentSnapshot>
    )

    data class StatusSnapshot(
        val serviceStatus: String,
        val monitoringEnabled: Boolean,
        val ownerEnrolled: Boolean,
        val activeUserId: String?,
        val lastDecision: String?,
        val lastReason: String,
        val lastFaceCount: Int,
        val unknownStreak: Int,
        val lastIncidentId: String?,
        val incidentCount: Int,
        val camera: CapabilitySnapshot,
        val screenshot: CapabilitySnapshot,
        val ocr: CapabilitySnapshot,
        val email: CapabilitySnapshot,
        val gpu: GpuSnapshot,
        val config: ConfigSnapshot
    )

    data class CapabilitySnapshot(
        val state: String,
        val detail: String
    )

    data class GpuSnapshot(
        val preferGpu: Boolean,
        val available: Boolean,
        val activeBackend: String,
        val detail: String
    )

    data class ConfigSnapshot(
        val checkIntervalMs: Long,
        val debounceUnknownFrames: Int,
        val alertCooldownSeconds: Long,
        val storageRoot: String,
        val emailRecipient: String,
        val ocrLanguage: String,
        val preferGpu: Boolean,
        val displayServer: String
    )

    data class IncidentSnapshot(
        val incidentId: String,
        val createdAt: String,
        val decision: String,
        val reason: String,
        val tags: List<String>,
        val activeWindowTitle: String,
        val activeProcessName: String,
        val incidentDirectory: String,
        val webcamPhotoPath: String,
        val screenshotPath: String
    )

    data class ActionResult(
        val headline: String,
        val detail: String
    )

    fun refresh(): Snapshot {
        val statusNode = objectMapper.readTree(apiClient.get("/vision-security/status"))
        val incidentsNode = objectMapper.readTree(apiClient.get("/vision-security/incidents?limit=10"))

        return Snapshot(
            refreshedAt = Instant.now(),
            status = parseStatus(statusNode),
            incidents = incidentsNode.map(::parseIncident)
        )
    }

    fun startMonitoring(): ActionResult {
        val payload = objectMapper.readTree(apiClient.post("/vision-security/monitoring/start", "{}"))
        return ActionResult("Monitoring started", payload.path("lastReason").asText("Vision security monitoring is running"))
    }

    fun stopMonitoring(): ActionResult {
        val payload = objectMapper.readTree(apiClient.post("/vision-security/monitoring/stop", "{}"))
        return ActionResult("Monitoring stopped", payload.path("lastReason").asText("Vision security monitoring is paused"))
    }

    fun captureEnrollment(sampleCount: Int = 6): ActionResult {
        val payload = objectMapper.readTree(apiClient.post("/vision-security/enrollment/capture", """{"sampleCount":$sampleCount}"""))
        return ActionResult(
            "Owner enrollment updated",
            "Captured ${payload.path("sampleCount").asInt(sampleCount)} samples at ${payload.path("sampleDirectory").asText()}"
        )
    }

    fun resetEnrollment(): ActionResult {
        apiClient.post("/vision-security/enrollment/reset", "{}")
        return ActionResult("Owner enrollment cleared", "Stored owner samples were removed for the current account")
    }

    fun capturePipelineSnapshot(): ActionResult {
        val payload = objectMapper.readTree(apiClient.post("/vision-security/pipeline/capture", "{}"))
        return ActionResult(
            "Pipeline snapshot exported",
            payload.path("outputDirectory").asText("Vision pipeline stages were exported")
        )
    }

    fun sendTestAlert(): ActionResult {
        val payload = objectMapper.readTree(apiClient.post("/vision-security/alerts/test", "{}"))
        val headline = if (payload.path("sent").asBoolean(false)) "Test alert sent" else "Test alert not sent"
        return ActionResult(headline, payload.path("message").asText("Vision security alert test finished"))
    }

    private fun parseStatus(node: JsonNode): StatusSnapshot {
        return StatusSnapshot(
            serviceStatus = node.path("serviceStatus").asText("UNKNOWN"),
            monitoringEnabled = node.path("monitoringEnabled").asBoolean(false),
            ownerEnrolled = node.path("ownerEnrolled").asBoolean(false),
            activeUserId = textOrNull(node, "activeUserId"),
            lastDecision = textOrNull(node, "lastDecision"),
            lastReason = node.path("lastReason").asText("No vision security activity yet"),
            lastFaceCount = node.path("lastFaceCount").asInt(0),
            unknownStreak = node.path("unknownStreak").asInt(0),
            lastIncidentId = textOrNull(node, "lastIncidentId"),
            incidentCount = node.path("incidentCount").asInt(0),
            camera = parseCapability(node.path("camera")),
            screenshot = parseCapability(node.path("screenshot")),
            ocr = parseCapability(node.path("ocr")),
            email = parseCapability(node.path("email")),
            gpu = GpuSnapshot(
                preferGpu = node.path("gpu").path("preferGpu").asBoolean(false),
                available = node.path("gpu").path("available").asBoolean(false),
                activeBackend = node.path("gpu").path("activeBackend").asText("cpu"),
                detail = node.path("gpu").path("detail").asText("")
            ),
            config = ConfigSnapshot(
                checkIntervalMs = node.path("config").path("checkIntervalMs").asLong(2_000L),
                debounceUnknownFrames = node.path("config").path("debounceUnknownFrames").asInt(3),
                alertCooldownSeconds = node.path("config").path("alertCooldownSeconds").asLong(60L),
                storageRoot = node.path("config").path("storageRoot").asText(""),
                emailRecipient = node.path("config").path("emailRecipient").asText(""),
                ocrLanguage = node.path("config").path("ocrLanguage").asText("eng"),
                preferGpu = node.path("config").path("preferGpu").asBoolean(false),
                displayServer = node.path("config").path("displayServer").asText("unknown")
            )
        )
    }

    private fun parseCapability(node: JsonNode): CapabilitySnapshot {
        return CapabilitySnapshot(
            state = node.path("state").asText("UNKNOWN"),
            detail = node.path("detail").asText("")
        )
    }

    private fun parseIncident(node: JsonNode): IncidentSnapshot {
        return IncidentSnapshot(
            incidentId = node.path("incidentId").asText(""),
            createdAt = node.path("createdAt").asText(""),
            decision = node.path("decision").asText(""),
            reason = node.path("reason").asText(""),
            tags = node.path("semanticTags").map { it.asText() },
            activeWindowTitle = node.path("screenContext").path("activeWindowTitle").asText(""),
            activeProcessName = node.path("screenContext").path("activeProcessName").asText(""),
            incidentDirectory = node.path("incidentDirectory").asText(""),
            webcamPhotoPath = node.path("webcamPhotoPath").asText(""),
            screenshotPath = node.path("screenshotPath").asText("")
        )
    }

    private fun textOrNull(node: JsonNode, field: String): String? {
        return if (node.path(field).isMissingNode || node.path(field).isNull) null else node.path(field).asText()
    }
}
