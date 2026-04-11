package org.jarvis.desktop.features.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.launcher.JarvisPaths
import org.jarvis.launcher.LauncherConfig
import org.jarvis.launcher.LauncherSettings
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

class AiReadModel(
    private val launcherSettingsProvider: () -> LauncherSettings = { LauncherConfig(JarvisPaths.launcherConfig).load() },
    private val llmServiceBaseUrl: String = resolveDefaultLlmBaseUrl(),
    private val memoryServiceBaseUrl: String = "http://127.0.0.1:8093",
    private val tokenProvider: () -> String? = { null }
) {

    companion object {
        private const val DEFAULT_LOCAL_LLM_URL = "http://127.0.0.1:8091"

        /**
         * In local mode, talk directly to llm-service on its native port.
         * In k8s mode, route through api-gateway which proxies to llm-service.
         */
        fun resolveDefaultLlmBaseUrl(): String {
            return if (JarvisPaths.isLocalRuntime()) DEFAULT_LOCAL_LLM_URL
            else JarvisPaths.getApiGatewayUrl()
        }
    }

    enum class AiStatus {
        DOWN, STARTING, READY, DEGRADED, ERROR, DISABLED
    }

    data class Snapshot(
        val refreshedAt: Instant,
        val overallStatus: AiStatus,
        val overallReason: String,
        val llm: LlmStatus,
        val memory: MemoryStatus,
        val embedding: EmbeddingStatus,
        val gpu: GpuStatus,
        val model: ModelInfo,
        val config: AiConfig,
        val lifecycle: LifecycleStatus,
        val admission: AdmissionStatus,
        val runtimeRaw: String
    )

    data class LifecycleStatus(
        val state: String,
        val reason: String,
        val warmupComplete: Boolean,
        val usable: Boolean
    )

    data class AdmissionStatus(
        val activeInferences: Int,
        val queueDepth: Int,
        val totalAdmitted: Long,
        val rejectedCount: Long,
        val availablePermits: Int
    )

    data class LlmStatus(
        val available: Boolean,
        val enabled: Boolean,
        val status: String,
        val reason: String,
        val provider: String,
        val baseUrl: String
    )

    data class MemoryStatus(
        val enabled: Boolean,
        val serviceEnabled: Boolean,
        val available: Boolean,
        val status: String,
        val reason: String
    )

    data class EmbeddingStatus(
        val available: Boolean,
        val model: String,
        val dimension: Int?,
        val reason: String
    )

    data class GpuStatus(
        val available: Boolean,
        val device: String,
        val configuredGpuLayers: Int?,
        val effectiveGpuLayers: Int?,
        val gpuName: String,
        val driverVersion: String,
        val cudaVersion: String,
        val readinessStatus: String,
        val readinessReason: String
    )

    data class ModelInfo(
        val llmModel: String,
        val effectiveLlmModel: String,
        val embeddingModel: String,
        val provider: String,
        val stackId: String
    )

    data class AiConfig(
        val llmEnabled: Boolean,
        val memoryEnabled: Boolean,
        val gpuEnabled: Boolean,
        val gpuMode: String,
        val autoStart: Boolean
    )

    data class ActionResult(
        val headline: String,
        val detail: String,
        val success: Boolean
    )

    private val logger = LoggerFactory.getLogger(AiReadModel::class.java)
    private val objectMapper = jacksonObjectMapper()
    @Volatile private var lastRuntimeHttpStatus: Int? = null

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    fun refresh(): Snapshot {
        val settings = loadSettings()
        val config = AiConfig(
            llmEnabled = settings.enableLlm,
            memoryEnabled = settings.enableMemory,
            gpuEnabled = settings.enableGpu,
            gpuMode = resolveGpuMode(settings),
            autoStart = settings.enableLlm
        )

        val runtimeJson = fetchRuntimeStatus()
        if (runtimeJson != null) {
            return parseRuntimeSnapshot(runtimeJson, config)
        }

        val healthJson = fetchHealthStatus()
        if (healthJson != null) {
            return parseHealthSnapshot(healthJson, config)
        }

        if (!settings.enableLlm && !settings.enableMemory) {
            return disabledSnapshot(config)
        }

        return unreachableSnapshot(config, false)
    }

    fun startAi(): ActionResult {
        if (!JarvisPaths.isLocalRuntime()) {
            return ActionResult(
                "Not available in Kubernetes mode",
                "AI lifecycle is managed by Kubernetes. Use kubectl to scale AI workloads or re-run the launcher with AI enabled.",
                false
            )
        }
        return runAiScript("ai-up.sh", "Starting AI services")
    }

    fun stopAi(): ActionResult {
        if (!JarvisPaths.isLocalRuntime()) {
            return ActionResult(
                "Not available in Kubernetes mode",
                "AI lifecycle is managed by Kubernetes. Use kubectl to scale AI workloads to 0.",
                false
            )
        }
        return runAiScript("ai-down.sh", "Stopping AI services")
    }

    fun restartAi(): ActionResult {
        if (!JarvisPaths.isLocalRuntime()) {
            return ActionResult(
                "Not available in Kubernetes mode",
                "AI lifecycle is managed by Kubernetes. Use kubectl rollout restart to restart AI workloads.",
                false
            )
        }
        val stopResult = stopAi()
        if (!stopResult.success) {
            return stopResult
        }
        return startAi()
    }

    private fun runAiScript(scriptName: String, operation: String): ActionResult {
        return try {
            val projectRoot = JarvisPaths.getProjectRoot()
            val scriptPath = projectRoot.resolve("scripts/$scriptName")
            if (!Files.exists(scriptPath)) {
                return ActionResult(
                    "$operation failed",
                    "Script not found: $scriptPath",
                    false
                )
            }

            val settings = loadSettings()
            val envMap = mutableMapOf<String, String>()
            envMap["ENABLE_LLM"] = settings.enableLlm.toString()
            envMap["ENABLE_MEMORY"] = settings.enableMemory.toString()
            if (settings.enableGpu) {
                envMap["N_GPU_LAYERS"] = "-1"
                envMap["DEVICE"] = "auto"
            } else {
                envMap["N_GPU_LAYERS"] = "0"
                envMap["DEVICE"] = "cpu"
            }

            val logDir = JarvisPaths.logs.resolve("local-runtime")
            Files.createDirectories(logDir)
            val logFile = logDir.resolve("$scriptName.log").toFile()

            val process = ProcessBuilder("bash", scriptPath.toString())
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .also { pb -> pb.environment().putAll(envMap) }
                .start()

            ActionResult(
                "$operation initiated",
                "PID ${process.pid()} — log at ${logFile.absolutePath}",
                true
            )
        } catch (e: Exception) {
            logger.error("Failed to run AI script: $scriptName", e)
            ActionResult(
                "$operation failed",
                e.message ?: "Unknown error executing $scriptName",
                false
            )
        }
    }

    private fun fetchRuntimeStatus(): JsonNode? {
        return try {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create("$llmServiceBaseUrl/api/v1/llm/runtime"))
                .timeout(Duration.ofSeconds(4))
                .GET()
            tokenProvider()?.let { token ->
                builder.header("Authorization", "Bearer $token")
            }
            val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            lastRuntimeHttpStatus = response.statusCode()
            if (response.statusCode() in 200..299) {
                objectMapper.readTree(response.body())
            } else {
                logger.debug("LLM runtime returned HTTP {}", response.statusCode())
                null
            }
        } catch (e: Exception) {
            lastRuntimeHttpStatus = null
            logger.debug("LLM runtime unreachable: {}", e.message)
            null
        }
    }

    private fun fetchHealthStatus(): JsonNode? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$llmServiceBaseUrl/api/v1/llm/health"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..599) {
                objectMapper.readTree(response.body())
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRuntimeSnapshot(root: JsonNode, config: AiConfig): Snapshot {
        val topStatus = root.path("status").asText("unknown")
        val llmNode = root.path("llm")
        val memoryNode = root.path("memory")
        val embeddingNode = root.path("embedding")
        val gpuNode = root.path("gpu")
        val stackNode = root.path("localDefaultStack")
        val lifecycleNode = root.path("lifecycle")
        val admissionNode = root.path("admission")

        val llm = LlmStatus(
            available = llmNode.path("available").asBoolean(false),
            enabled = llmNode.path("enabled").asBoolean(false),
            status = llmNode.path("status").asText("unknown"),
            reason = llmNode.path("reason").asText(""),
            provider = llmNode.path("effectiveProvider").asText(llmNode.path("configuredProvider").asText("unknown")),
            baseUrl = llmNode.path("baseUrl").asText("")
        )

        val memory = MemoryStatus(
            enabled = memoryNode.path("enabled").asBoolean(false),
            serviceEnabled = memoryNode.path("serviceEnabled").asBoolean(false),
            available = memoryNode.path("available").asBoolean(false),
            status = memoryNode.path("status").asText("unknown"),
            reason = memoryNode.path("reason").asText("")
        )

        val embedding = EmbeddingStatus(
            available = embeddingNode.path("available").asBoolean(false),
            model = embeddingNode.path("effectiveModel").asText(embeddingNode.path("configuredModel").asText("unknown")),
            dimension = if (embeddingNode.has("dimension") && !embeddingNode.path("dimension").isNull)
                embeddingNode.path("dimension").asInt() else null,
            reason = embeddingNode.path("reason").asText("")
        )

        val gpu = GpuStatus(
            available = gpuNode.path("available").asBoolean(false),
            device = llmNode.path("device").asText(gpuNode.path("effectiveDevicePath").asText("cpu")),
            configuredGpuLayers = intOrNull(gpuNode, "configuredGpuLayers"),
            effectiveGpuLayers = intOrNull(gpuNode, "effectiveGpuLayers"),
            gpuName = gpuNode.path("gpuName").asText(""),
            driverVersion = gpuNode.path("driverVersion").asText(""),
            cudaVersion = llmNode.path("cudaVersion").asText(""),
            readinessStatus = gpuNode.path("readinessStatus").asText("unknown"),
            readinessReason = gpuNode.path("readinessReason").asText("")
        )

        val model = ModelInfo(
            llmModel = llmNode.path("configuredModel").asText("unknown"),
            effectiveLlmModel = llmNode.path("effectiveModel").asText(llmNode.path("configuredModel").asText("unknown")),
            embeddingModel = embeddingNode.path("configuredModel").asText("unknown"),
            provider = llmNode.path("effectiveProvider").asText("unknown"),
            stackId = stackNode.path("id").asText("")
        )

        val lifecycle = LifecycleStatus(
            state = lifecycleNode.path("state").asText("UNKNOWN"),
            reason = lifecycleNode.path("reason").asText(""),
            warmupComplete = lifecycleNode.path("warmup_complete").asBoolean(false),
            usable = lifecycleNode.path("usable").asBoolean(false)
        )

        val admission = AdmissionStatus(
            activeInferences = admissionNode.path("active_inferences").asInt(0),
            queueDepth = admissionNode.path("queue_depth").asInt(0),
            totalAdmitted = admissionNode.path("total_admitted").asLong(0),
            rejectedCount = admissionNode.path("rejected_count").asLong(0),
            availablePermits = admissionNode.path("available_permits").asInt(0)
        )

        val overallStatus = mapTopLevelStatus(topStatus, llm, memory)
        val overallReason = buildOverallReason(overallStatus, topStatus, llm, memory)

        return Snapshot(
            refreshedAt = Instant.now(),
            overallStatus = overallStatus,
            overallReason = overallReason,
            llm = llm,
            memory = memory,
            embedding = embedding,
            gpu = gpu,
            model = model,
            config = config,
            lifecycle = lifecycle,
            admission = admission,
            runtimeRaw = topStatus
        )
    }

    private fun parseHealthSnapshot(health: JsonNode, config: AiConfig): Snapshot {
        val status = health.path("status").asText("unknown")
        val lifecycleState = health.path("lifecycle_state").asText("UNKNOWN")
        val lifecycleReason = health.path("lifecycle_reason").asText("")
        val warmupComplete = health.path("warmup_complete").asBoolean(false)
        val llmAvailable = health.path("llm_server_available").asBoolean(false)
        val memoryAvailable = health.path("memory_available").asBoolean(false)
        val memoryEnabled = health.path("memory_enabled").asBoolean(false)
        val activeInferences = health.path("active_inferences").asInt(0)
        val queueDepth = health.path("queue_depth").asInt(0)
        val effectiveProvider = health.path("effective_provider").asText(
            health.path("configured_provider").asText("unknown")
        )
        val configuredModel = health.path("configured_model").asText("unknown")
        val effectiveModel = health.path("effective_model").asText("unknown")

        val runtimeForbidden = lastRuntimeHttpStatus == 403
        val diagnosticsNote = if (runtimeForbidden)
            " Detailed runtime diagnostics require service authentication."
        else
            " Detailed runtime diagnostics are unavailable."

        val overallStatus = when {
            lifecycleState == "READY" -> AiStatus.READY
            status == "healthy" -> AiStatus.READY
            status == "degraded" -> AiStatus.DEGRADED
            llmAvailable -> AiStatus.DEGRADED
            else -> AiStatus.STARTING
        }

        val overallReason = when (overallStatus) {
            AiStatus.READY -> "AI services are healthy and ready (from /health).$diagnosticsNote"
            AiStatus.DEGRADED -> buildString {
                append("AI stack is partially available (from /health). ")
                if (!llmAvailable) append("LLM server not available. ")
                if (memoryEnabled && !memoryAvailable) append("Memory service not available. ")
                append(diagnosticsNote)
            }
            else -> "AI services are starting up (lifecycle: $lifecycleState).$diagnosticsNote"
        }

        return Snapshot(
            refreshedAt = Instant.now(),
            overallStatus = overallStatus,
            overallReason = overallReason,
            llm = LlmStatus(
                available = llmAvailable,
                enabled = true,
                status = if (llmAvailable) "ready" else "starting",
                reason = if (llmAvailable) "Healthy" else lifecycleReason.ifBlank { "Waiting for LLM server" },
                provider = effectiveProvider,
                baseUrl = llmServiceBaseUrl
            ),
            memory = MemoryStatus(
                enabled = memoryEnabled,
                serviceEnabled = memoryEnabled,
                available = memoryAvailable,
                status = if (memoryAvailable) "ready" else if (memoryEnabled) "starting" else "disabled",
                reason = if (memoryAvailable) "Healthy" else if (!memoryEnabled) "Memory disabled" else "Waiting"
            ),
            embedding = EmbeddingStatus(
                available = llmAvailable,
                model = "unknown",
                dimension = null,
                reason = if (llmAvailable) "Available (details require /runtime)" else "Unavailable"
            ),
            gpu = GpuStatus(
                available = false,
                device = "unknown",
                configuredGpuLayers = null,
                effectiveGpuLayers = null,
                gpuName = "",
                driverVersion = "",
                cudaVersion = "",
                readinessStatus = "unknown",
                readinessReason = "GPU details require /runtime endpoint"
            ),
            model = ModelInfo(
                llmModel = configuredModel,
                effectiveLlmModel = effectiveModel,
                embeddingModel = "unknown",
                provider = effectiveProvider,
                stackId = ""
            ),
            config = config,
            lifecycle = LifecycleStatus(
                state = lifecycleState,
                reason = lifecycleReason,
                warmupComplete = warmupComplete,
                usable = overallStatus == AiStatus.READY || overallStatus == AiStatus.DEGRADED
            ),
            admission = AdmissionStatus(
                activeInferences = activeInferences,
                queueDepth = queueDepth,
                totalAdmitted = 0,
                rejectedCount = 0,
                availablePermits = 0
            ),
            runtimeRaw = status
        )
    }

    private fun disabledSnapshot(config: AiConfig): Snapshot {
        return Snapshot(
            refreshedAt = Instant.now(),
            overallStatus = AiStatus.DISABLED,
            overallReason = "AI services are disabled in launcher settings. Enable LLM or Memory in the launcher to activate.",
            llm = LlmStatus(false, false, "disabled", "LLM disabled in launcher settings", "n/a", ""),
            memory = MemoryStatus(false, false, false, "disabled", "Memory disabled in launcher settings"),
            embedding = EmbeddingStatus(false, "n/a", null, "Memory disabled"),
            gpu = GpuStatus(false, "n/a", null, null, "", "", "", "n/a", "AI disabled"),
            model = ModelInfo("n/a", "n/a", "n/a", "n/a", ""),
            config = config,
            lifecycle = LifecycleStatus("DOWN", "AI disabled", false, false),
            admission = AdmissionStatus(0, 0, 0, 0, 0),
            runtimeRaw = "disabled"
        )
    }

    private fun unreachableSnapshot(config: AiConfig, healthReachable: Boolean): Snapshot {
        val reason = if (healthReachable) {
            "LLM service is reachable at $llmServiceBaseUrl but runtime details are unavailable. The service may still be starting."
        } else {
            "LLM service is not reachable at $llmServiceBaseUrl. AI services may not be running. Use 'Start AI' to bring them up."
        }
        val status = if (healthReachable) AiStatus.STARTING else AiStatus.DOWN

        return Snapshot(
            refreshedAt = Instant.now(),
            overallStatus = status,
            overallReason = reason,
            llm = LlmStatus(false, config.llmEnabled, if (healthReachable) "starting" else "down", reason, "unknown", llmServiceBaseUrl),
            memory = MemoryStatus(config.memoryEnabled, config.memoryEnabled, false, "unknown", "Cannot reach AI runtime"),
            embedding = EmbeddingStatus(false, "unknown", null, "Cannot reach AI runtime"),
            gpu = GpuStatus(false, "unknown", null, null, "", "", "", "unknown", "Cannot reach AI runtime"),
            model = ModelInfo("unknown", "unknown", "unknown", "unknown", ""),
            config = config,
            lifecycle = LifecycleStatus(if (healthReachable) "STARTING" else "DOWN", reason, false, false),
            admission = AdmissionStatus(0, 0, 0, 0, 0),
            runtimeRaw = if (healthReachable) "starting" else "down"
        )
    }

    private fun mapTopLevelStatus(raw: String, llm: LlmStatus, memory: MemoryStatus): AiStatus {
        return when (raw.lowercase()) {
            "ready" -> AiStatus.READY
            "llm-only" -> if (memory.enabled) AiStatus.DEGRADED else AiStatus.READY
            "partial" -> AiStatus.DEGRADED
            "degraded" -> AiStatus.DEGRADED
            "disabled" -> AiStatus.DISABLED
            else -> if (llm.available) AiStatus.DEGRADED else AiStatus.ERROR
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun buildOverallReason(status: AiStatus, raw: String, llm: LlmStatus, memory: MemoryStatus): String {
        return when (status) {
            AiStatus.READY -> "All configured AI services are healthy and ready for inference."
            AiStatus.DEGRADED -> buildString {
                append("AI stack is partially available. ")
                if (!llm.available) append("LLM: ${llm.reason.ifBlank { "unavailable" }}. ")
                if (memory.enabled && !memory.available) append("Memory: ${memory.reason.ifBlank { "unavailable" }}. ")
            }
            AiStatus.ERROR -> "AI services have errors. ${llm.reason.ifBlank { "LLM is not responding." }}"
            AiStatus.DISABLED -> "AI services are disabled in configuration."
            AiStatus.DOWN -> "AI services are not running."
            AiStatus.STARTING -> "AI services are starting up."
        }
    }

    private fun loadSettings(): LauncherSettings {
        return try {
            launcherSettingsProvider()
        } catch (_: Exception) {
            LauncherSettings()
        }
    }

    private fun resolveGpuMode(settings: LauncherSettings): String {
        return if (settings.enableGpu) "AUTO" else "CPU_ONLY"
    }

    private fun intOrNull(node: JsonNode, field: String): Int? {
        val child = node.path(field)
        return if (child.isMissingNode || child.isNull) null else child.asInt()
    }

}
