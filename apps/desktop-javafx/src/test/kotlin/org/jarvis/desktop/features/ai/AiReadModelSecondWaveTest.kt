package org.jarvis.desktop.features.ai

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.launcher.JarvisPaths
import org.jarvis.launcher.LauncherSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * SECOND-WAVE coverage for [AiReadModel]: targets branches the original
 * [AiReadModelTest] leaves uncovered — the disabled-settings snapshot, the
 * remaining `mapTopLevelStatus` cases (partial / degraded / disabled), the
 * `/health` fallback's `llm-available-only` and `starting` overall-status
 * branches, and the Kubernetes-mode short-circuits of the lifecycle actions.
 *
 * All HTTP is driven through a loopback [MockWebServer]; no JavaFX toolkit is
 * needed. Lifecycle-action tests only exercise the k8s branch (a pure string
 * return) and skip themselves when the environment reports local runtime, so
 * no real `bash` subprocess is ever spawned.
 */
class AiReadModelSecondWaveTest {

    private fun readModel(
        server: MockWebServer,
        settings: LauncherSettings = LauncherSettings()
    ): AiReadModel {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        return AiReadModel(
            launcherSettingsProvider = { settings },
            llmServiceBaseUrl = baseUrl,
            tokenProvider = { null }
        )
    }

    private fun enqueueRuntime(server: MockWebServer, body: String) {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        )
    }

    private inline fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        try {
            server.start()
            block(server)
        } finally {
            server.shutdown()
        }
    }

    // ── disabled-settings snapshot ────────────────────────────────────

    @Test
    fun `refresh returns a DISABLED snapshot when both runtime is unreachable and settings disable AI`() {
        // Server started then shut down -> both runtime + health calls fail with a
        // connection error, and with LLM+Memory disabled we fall to disabledSnapshot().
        val server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString().removeSuffix("/")
        server.shutdown()

        val model = AiReadModel(
            launcherSettingsProvider = { LauncherSettings(enableLlm = false, enableMemory = false, enableGpu = false) },
            llmServiceBaseUrl = baseUrl,
            tokenProvider = { null }
        )

        val snapshot = model.refresh()

        assertEquals(AiReadModel.AiStatus.DISABLED, snapshot.overallStatus)
        assertEquals("disabled", snapshot.runtimeRaw)
        assertTrue(snapshot.overallReason.contains("disabled in launcher settings"))
        assertFalse(snapshot.config.llmEnabled)
        assertEquals("CPU_ONLY", snapshot.config.gpuMode)
        assertEquals("DOWN", snapshot.lifecycle.state)
    }

    // ── mapTopLevelStatus remaining cases ─────────────────────────────

    @Test
    fun `refresh maps a partial runtime status to DEGRADED`() {
        withServer { server ->
            enqueueRuntime(server, """{ "status": "partial", "llm": { "available": false, "reason": "loading" } }""")

            val snapshot = readModel(server).refresh()

            assertEquals(AiReadModel.AiStatus.DEGRADED, snapshot.overallStatus)
            assertTrue(snapshot.overallReason.contains("partially available"))
        }
    }

    @Test
    fun `refresh maps a degraded runtime status to DEGRADED`() {
        withServer { server ->
            enqueueRuntime(server, """{ "status": "degraded", "llm": { "available": true } }""")

            assertEquals(AiReadModel.AiStatus.DEGRADED, readModel(server).refresh().overallStatus)
        }
    }

    @Test
    fun `refresh maps a disabled runtime status to DISABLED with the configuration reason`() {
        withServer { server ->
            enqueueRuntime(server, """{ "status": "disabled", "llm": { "available": false } }""")

            val snapshot = readModel(server).refresh()

            assertEquals(AiReadModel.AiStatus.DISABLED, snapshot.overallStatus)
            assertEquals("AI services are disabled in configuration.", snapshot.overallReason)
            assertEquals("disabled", snapshot.runtimeRaw)
        }
    }

    @Test
    fun `refresh falls back to configured provider and stack defaults when runtime omits them`() {
        withServer { server ->
            // effectiveProvider absent -> configuredProvider; gpu fields absent -> null;
            // dimension present but null -> null; stack id absent -> "".
            enqueueRuntime(
                server,
                """
                {
                  "status": "ready",
                  "llm": { "available": true, "configuredProvider": "vllm" },
                  "embedding": { "available": true, "dimension": null }
                }
                """.trimIndent()
            )

            val snapshot = readModel(server).refresh()

            assertEquals("vllm", snapshot.llm.provider)
            assertNull(snapshot.embedding.dimension)
            assertNull(snapshot.gpu.configuredGpuLayers)
            assertEquals("", snapshot.model.stackId)
        }
    }

    // ── /health fallback overall-status branches ──────────────────────

    @Test
    fun `health fallback maps llm-available-only to DEGRADED even without healthy or degraded status`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(404)) // runtime unreachable
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """
                    { "status": "starting", "lifecycle_state": "STARTING",
                      "llm_server_available": true, "memory_available": false, "memory_enabled": false }
                    """.trimIndent()
                )
            )

            val snapshot = readModel(server).refresh()

            assertEquals(AiReadModel.AiStatus.DEGRADED, snapshot.overallStatus)
            // memory disabled -> disabled status + "Memory disabled" reason
            assertEquals("disabled", snapshot.memory.status)
            assertEquals("Memory disabled", snapshot.memory.reason)
            // llm available -> embedding reported available via /health
            assertTrue(snapshot.embedding.available)
            assertTrue(snapshot.lifecycle.usable)
        }
    }

    @Test
    fun `health fallback maps an all-down health payload to STARTING`() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(404)) // runtime unreachable
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """
                    { "status": "initializing", "lifecycle_state": "LOADING",
                      "llm_server_available": false, "memory_available": false, "memory_enabled": true }
                    """.trimIndent()
                )
            )

            val snapshot = readModel(server).refresh()

            assertEquals(AiReadModel.AiStatus.STARTING, snapshot.overallStatus)
            assertTrue(snapshot.overallReason.contains("starting up"))
            assertFalse(snapshot.lifecycle.usable)
            assertFalse(snapshot.embedding.available)
            // memory enabled but unavailable -> "starting" / "Waiting"
            assertEquals("starting", snapshot.memory.status)
            assertEquals("Waiting", snapshot.memory.reason)
        }
    }

    // ── lifecycle actions: Kubernetes-mode short-circuit ──────────────

    @Test
    fun `startAi refuses in Kubernetes mode without spawning a process`() {
        assumeTrue(!JarvisPaths.isLocalRuntime(), "environment reports local runtime — skip k8s-branch assertion")
        withServer { server ->
            val result = readModel(server).startAi()

            assertFalse(result.success)
            assertTrue(result.headline.contains("Kubernetes"))
            assertTrue(result.detail.contains("kubectl"))
        }
    }

    @Test
    fun `stopAi refuses in Kubernetes mode without spawning a process`() {
        assumeTrue(!JarvisPaths.isLocalRuntime(), "environment reports local runtime — skip k8s-branch assertion")
        withServer { server ->
            val result = readModel(server).stopAi()

            assertFalse(result.success)
            assertTrue(result.headline.contains("Kubernetes"))
        }
    }

    @Test
    fun `restartAi refuses in Kubernetes mode without spawning a process`() {
        assumeTrue(!JarvisPaths.isLocalRuntime(), "environment reports local runtime — skip k8s-branch assertion")
        withServer { server ->
            val result = readModel(server).restartAi()

            assertFalse(result.success)
            assertTrue(result.detail.contains("rollout restart"))
        }
    }
}
