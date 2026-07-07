package org.jarvis.desktop.features.ai

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.features.status.StatusLevel
import org.jarvis.launcher.LauncherSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [AiReadModel.refresh] parsing/degrade logic only. [AiReadModel.startAi] /
 * [AiReadModel.stopAi] / [AiReadModel.restartAi] are intentionally NOT exercised here:
 * they branch on [org.jarvis.launcher.JarvisPaths.isLocalRuntime] (real environment/
 * filesystem state) and, in local mode, spawn a real `bash` subprocess via
 * `ProcessBuilder` — not something that can be driven deterministically headlessly.
 */
class AiReadModelTest {

    private fun readModel(
        server: MockWebServer,
        settings: LauncherSettings = LauncherSettings(),
        memoryBaseUrl: String = "http://127.0.0.1:8093"
    ): AiReadModel {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        return AiReadModel(
            launcherSettingsProvider = { settings },
            llmServiceBaseUrl = baseUrl,
            memoryServiceBaseUrl = memoryBaseUrl,
            tokenProvider = { null }
        )
    }

    @Test
    fun `refresh parses a ready runtime payload into a READY snapshot`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "status": "ready",
                      "llm": {
                        "available": true, "enabled": true, "status": "ready", "reason": "",
                        "effectiveProvider": "llama-cpp", "baseUrl": "http://127.0.0.1:8091",
                        "device": "cuda:0", "cudaVersion": "12.8"
                      },
                      "memory": { "enabled": true, "serviceEnabled": true, "available": true, "status": "ready", "reason": "" },
                      "embedding": { "available": true, "effectiveModel": "bge-small", "dimension": 384, "reason": "" },
                      "gpu": {
                        "available": true, "effectiveDevicePath": "cuda:0", "configuredGpuLayers": -1,
                        "effectiveGpuLayers": 32, "gpuName": "RTX 5070", "driverVersion": "570.1",
                        "readinessStatus": "READY", "readinessReason": ""
                      },
                      "localDefaultStack": { "id": "qwen3-14b" },
                      "lifecycle": { "state": "READY", "reason": "", "warmup_complete": true, "usable": true },
                      "admission": { "active_inferences": 1, "queue_depth": 0, "total_admitted": 42, "rejected_count": 0, "available_permits": 3 }
                    }
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val snapshot = readModel(server).refresh()

            assertEquals(AiReadModel.AiStatus.READY, snapshot.overallStatus)
            assertNotNull(snapshot.refreshedAt)
            assertTrue(snapshot.llm.available)
            assertEquals("llama-cpp", snapshot.llm.provider)
            assertTrue(snapshot.memory.available)
            assertEquals("bge-small", snapshot.embedding.model)
            assertEquals(384, snapshot.embedding.dimension)
            assertTrue(snapshot.gpu.available)
            assertEquals(32, snapshot.gpu.effectiveGpuLayers)
            assertEquals(-1, snapshot.gpu.configuredGpuLayers)
            assertEquals("12.8", snapshot.gpu.cudaVersion)
            assertEquals("qwen3-14b", snapshot.model.stackId)
            assertEquals("READY", snapshot.lifecycle.state)
            assertTrue(snapshot.lifecycle.usable)
            assertEquals(1, snapshot.admission.activeInferences)
            assertEquals(42L, snapshot.admission.totalAdmitted)
            assertTrue(snapshot.config.llmEnabled)
            assertTrue(snapshot.config.gpuEnabled)
            assertEquals("AUTO", snapshot.config.gpuMode)
            assertEquals("ready", snapshot.runtimeRaw)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `refresh maps llm-only runtime status to DEGRADED when memory is enabled`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{ "status": "llm-only", "llm": { "available": true, "reason": "" }, "memory": { "enabled": true, "available": false, "reason": "down" } }"""
                )
        )

        try {
            server.start()
            val snapshot = readModel(server).refresh()

            assertEquals(AiReadModel.AiStatus.DEGRADED, snapshot.overallStatus)
            assertTrue(snapshot.overallReason.contains("Memory: down"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `refresh maps llm-only runtime status to READY when memory is disabled`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "status": "llm-only", "llm": { "available": true }, "memory": { "enabled": false } }""")
        )

        try {
            server.start()
            val snapshot = readModel(server).refresh()

            assertEquals(AiReadModel.AiStatus.READY, snapshot.overallStatus)
            assertEquals("All configured AI services are healthy and ready for inference.", snapshot.overallReason)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `refresh maps unrecognized runtime status to ERROR when llm is unavailable`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "status": "weird", "llm": { "available": false, "reason": "crashed" } }""")
        )

        try {
            server.start()
            val snapshot = readModel(server).refresh()

            assertEquals(AiReadModel.AiStatus.ERROR, snapshot.overallStatus)
            assertTrue(snapshot.overallReason.contains("crashed"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `refresh treats missing gpu layer fields as null rather than zero`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "status": "degraded", "gpu": { "available": false } }""")
        )

        try {
            server.start()
            val snapshot = readModel(server).refresh()

            assertNull(snapshot.gpu.configuredGpuLayers)
            assertNull(snapshot.gpu.effectiveGpuLayers)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `refresh falls back to health endpoint when runtime is unreachable`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "status": "healthy", "lifecycle_state": "READY", "warmup_complete": true,
                      "llm_server_available": true, "memory_available": true, "memory_enabled": true,
                      "active_inferences": 2, "queue_depth": 1,
                      "effective_provider": "llama-cpp", "configured_model": "qwen3-14b", "effective_model": "qwen3-14b"
                    }
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val snapshot = readModel(server).refresh()

            assertEquals(AiReadModel.AiStatus.READY, snapshot.overallStatus)
            assertTrue(snapshot.overallReason.contains("healthy and ready"))
            assertTrue(snapshot.overallReason.contains("Detailed runtime diagnostics are unavailable"))
            assertTrue(snapshot.llm.available)
            assertEquals("llama-cpp", snapshot.llm.provider)
            assertEquals(2, snapshot.admission.activeInferences)
            assertEquals("qwen3-14b", snapshot.model.effectiveLlmModel)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `refresh health fallback reports a degraded reason when llm is unavailable`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{ "status": "degraded", "lifecycle_state": "DEGRADED", "llm_server_available": false, "memory_available": false, "memory_enabled": true }"""
                )
        )

        try {
            server.start()
            val snapshot = readModel(server).refresh()

            assertEquals(AiReadModel.AiStatus.DEGRADED, snapshot.overallStatus)
            assertTrue(snapshot.overallReason.contains("LLM server not available"))
            assertTrue(snapshot.overallReason.contains("Memory service not available"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `refresh health fallback reports authentication note when runtime returned 403`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "status": "healthy", "lifecycle_state": "READY", "llm_server_available": true }""")
        )

        try {
            server.start()
            val snapshot = readModel(server).refresh()

            assertTrue(snapshot.overallReason.contains("require service authentication"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `refresh swallows connection failures and still returns a snapshot`() {
        val server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString().removeSuffix("/")
        server.shutdown()

        // Server is now shut down, so both HTTP calls fail with a connection error.
        val model = AiReadModel(
            launcherSettingsProvider = { LauncherSettings() },
            llmServiceBaseUrl = baseUrl,
            tokenProvider = { null }
        )

        val snapshot = model.refresh()
        assertEquals(AiReadModel.AiStatus.DOWN, snapshot.overallStatus)
    }

    @Test
    fun `AiStatus toStatusLevel maps every value onto the canonical vocabulary`() {
        assertEquals(StatusLevel.UP, AiReadModel.AiStatus.READY.toStatusLevel())
        assertEquals(StatusLevel.UNKNOWN, AiReadModel.AiStatus.STARTING.toStatusLevel())
        assertEquals(StatusLevel.DEGRADED, AiReadModel.AiStatus.DEGRADED.toStatusLevel())
        assertEquals(StatusLevel.DOWN, AiReadModel.AiStatus.DOWN.toStatusLevel())
        assertEquals(StatusLevel.DOWN, AiReadModel.AiStatus.ERROR.toStatusLevel())
        assertEquals(StatusLevel.DISABLED, AiReadModel.AiStatus.DISABLED.toStatusLevel())
    }

    private fun gpu(
        available: Boolean = false,
        device: String = "unknown",
        readinessStatus: String = "unknown",
        gpuName: String = ""
    ) = AiReadModel.GpuStatus(
        available = available,
        device = device,
        configuredGpuLayers = null,
        effectiveGpuLayers = null,
        gpuName = gpuName,
        driverVersion = "",
        cudaVersion = "",
        readinessStatus = readinessStatus,
        readinessReason = ""
    )

    @Test
    fun `GpuStatus toStatusLevel reports UP when the backend confirms the GPU is active`() {
        assertEquals(StatusLevel.UP, gpu(available = true, device = "cuda:0", readinessStatus = "READY").toStatusLevel())
    }

    @Test
    fun `GpuStatus toStatusLevel reports UP when readiness is verified even if not yet marked available`() {
        assertEquals(
            StatusLevel.UP,
            gpu(available = false, device = "cuda:0", readinessStatus = "verified").toStatusLevel()
        )
    }

    @Test
    fun `GpuStatus toStatusLevel reports UNKNOWN rather than UNAVAILABLE when the runtime endpoint could not be reached`() {
        // "unknown" (no confirmed answer) must never be conflated with "unavailable"
        // (a confirmed negative answer) — that conflation is exactly the GPU
        // contradiction this mapping exists to prevent.
        val level = gpu(available = false, device = "unknown", readinessStatus = "unknown").toStatusLevel()
        assertEquals(StatusLevel.UNKNOWN, level)
        assertFalse(level.isHealthy)
    }

    @Test
    fun `GpuStatus toStatusLevel reports DISABLED for an intentional cpu-only configuration`() {
        val level = gpu(available = false, device = "cpu", readinessStatus = "not_checked").toStatusLevel()
        assertEquals(StatusLevel.DISABLED, level)
        assertTrue(level.isHealthy, "CPU-only is an intentional configuration, not a failure")
    }

    @Test
    fun `GpuStatus toStatusLevel reports UNAVAILABLE when a GPU was expected but confirmed absent`() {
        val level = gpu(available = false, device = "cuda:0", readinessStatus = "failed").toStatusLevel()
        assertEquals(StatusLevel.UNAVAILABLE, level)
        assertFalse(level.isHealthy)
    }

    @Test
    fun `LlmStatus toStatusLevel treats disabled-by-config as healthy, unavailable-while-enabled as DOWN`() {
        assertEquals(
            StatusLevel.UP,
            AiReadModel.LlmStatus(true, true, "ready", "", "llama-cpp", "").toStatusLevel()
        )
        assertEquals(
            StatusLevel.DISABLED,
            AiReadModel.LlmStatus(false, false, "disabled", "", "n/a", "").toStatusLevel()
        )
        assertEquals(
            StatusLevel.DOWN,
            AiReadModel.LlmStatus(false, true, "down", "", "llama-cpp", "").toStatusLevel()
        )
    }

    @Test
    fun `MemoryStatus toStatusLevel treats disabled-by-config as healthy, unavailable-while-enabled as DOWN`() {
        assertEquals(
            StatusLevel.UP,
            AiReadModel.MemoryStatus(true, true, true, "ready", "").toStatusLevel()
        )
        assertEquals(
            StatusLevel.DISABLED,
            AiReadModel.MemoryStatus(false, false, false, "disabled", "").toStatusLevel()
        )
        assertEquals(
            StatusLevel.DOWN,
            AiReadModel.MemoryStatus(true, true, false, "down", "").toStatusLevel()
        )
    }

    @Test
    fun `EmbeddingStatus toStatusLevel is UP when available and DOWN otherwise`() {
        assertEquals(StatusLevel.UP, AiReadModel.EmbeddingStatus(true, "bge-small", 384, "").toStatusLevel())
        assertEquals(StatusLevel.DOWN, AiReadModel.EmbeddingStatus(false, "unknown", null, "").toStatusLevel())
    }
}
