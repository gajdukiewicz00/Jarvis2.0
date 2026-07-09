package org.jarvis.desktop.e2e.ai

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jarvis.desktop.e2e.E2eFx
import org.jarvis.desktop.features.ai.AiReadModel
import org.jarvis.desktop.features.ai.AiView
import org.jarvis.launcher.LauncherSettings
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * True headless-UI E2E journeys for the AI Runtime screen ([AiView]).
 *
 * [AiView] takes an [AiReadModel]; we point that read model's `llmServiceBaseUrl`
 * at a [MockWebServer] standing in for llm-service / the api-gateway and pin its
 * launcher settings so construction is deterministic and never reads disk. The
 * view does no I/O in `init {}` — the fan-out fires only from `onRouteActivated()`
 * (the Refresh flow), which we invoke exactly as the shell does, then assert the
 * rendered scene graph reacted across every status card.
 *
 * The lifecycle Start/Stop/Restart buttons are deliberately NOT fired here: on a
 * local runtime they shell out to `ai-up.sh`/`ai-down.sh` via ProcessBuilder, and
 * the read/refresh path already exercises every `render*` branch of the view.
 */
class AiViewE2eTest {

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /** An [AiReadModel] whose HTTP calls hit [server] and whose settings are fixed. */
    private fun readModelFor(
        server: MockWebServer,
        settings: LauncherSettings = LauncherSettings()
    ): AiReadModel {
        val base = server.url("/").toString().removeSuffix("/")
        return AiReadModel(
            launcherSettingsProvider = { settings },
            llmServiceBaseUrl = base,
            memoryServiceBaseUrl = base,
            tokenProvider = { null }
        )
    }

    private fun buildView(readModel: AiReadModel): AiView = E2eFx.onFx { AiView(readModel) }

    @Test
    fun `route activation renders a fully-ready runtime snapshot`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return if (path.endsWith("/api/v1/llm/runtime")) {
                    json(
                        """
                        {
                          "status": "ready",
                          "llm": {
                            "available": true, "enabled": true, "status": "ready", "reason": "",
                            "effectiveProvider": "llama.cpp", "baseUrl": "http://127.0.0.1:8091",
                            "device": "cuda:0", "cudaVersion": "12.4",
                            "configuredModel": "qwen3-14b", "effectiveModel": "qwen3-14b-instruct"
                          },
                          "memory": {"enabled": true, "serviceEnabled": true, "available": true, "status": "ready", "reason": ""},
                          "embedding": {"available": true, "configuredModel": "bge-small", "effectiveModel": "bge-small", "dimension": 384, "reason": ""},
                          "gpu": {
                            "available": true, "effectiveDevicePath": "cuda:0",
                            "configuredGpuLayers": 35, "effectiveGpuLayers": 35,
                            "gpuName": "RTX 5070", "driverVersion": "555.42",
                            "readinessStatus": "verified", "readinessReason": "cuda ok"
                          },
                          "localDefaultStack": {"id": "local-cuda"},
                          "lifecycle": {"state": "READY", "reason": "warm", "warmup_complete": true, "usable": true},
                          "admission": {"active_inferences": 1, "queue_depth": 0, "total_admitted": 10, "rejected_count": 0, "available_permits": 4}
                        }
                        """.trimIndent()
                    )
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val view = buildView(readModelFor(server))
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "ready runtime snapshot rendered") {
                E2eFx.hasText(view, "qwen3-14b") && E2eFx.hasText(view, "Status updated")
            }

            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "qwen3-14b-instruct"), "effective model rendered")
                assertTrue(E2eFx.hasText(view, "RTX 5070"), "GPU name rendered")
                assertTrue(E2eFx.hasText(view, "cuda:0"), "GPU device rendered")
                assertTrue(E2eFx.hasText(view, "Active"), "GPU pill shows Active")
                assertTrue(E2eFx.hasText(view, "local-cuda"), "stack id rendered")
                assertTrue(E2eFx.hasText(view, "bge-small"), "embedding model rendered")
                assertTrue(E2eFx.hasText(view, "llama.cpp"), "LLM provider rendered")
                assertTrue(E2eFx.hasText(view, "1 inference(s)"), "admission active count rendered")
                assertTrue(
                    E2eFx.hasText(view, "All configured AI services are healthy"),
                    "overall READY reason rendered"
                )
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `partial runtime renders degraded, warming, disabled and unknown-gpu states`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return if (path.endsWith("/api/v1/llm/runtime")) {
                    json(
                        """
                        {
                          "status": "partial",
                          "llm": {
                            "available": false, "enabled": true, "status": "starting",
                            "reason": "loading weights", "effectiveProvider": "llama.cpp",
                            "baseUrl": "", "configuredModel": "qwen3-14b", "effectiveModel": "qwen3-14b"
                          },
                          "memory": {"enabled": true, "serviceEnabled": false, "available": false, "status": "disabled", "reason": "service off"},
                          "embedding": {"available": false, "configuredModel": "bge-small", "reason": "not ready"},
                          "gpu": {"available": false, "effectiveDevicePath": "unknown", "readinessStatus": "unknown", "readinessReason": "no probe"},
                          "localDefaultStack": {"id": ""},
                          "lifecycle": {"state": "WARMING_UP", "reason": "loading", "warmup_complete": false, "usable": false},
                          "admission": {"active_inferences": 0, "queue_depth": 2, "total_admitted": 0, "rejected_count": 1, "available_permits": 0}
                        }
                        """.trimIndent()
                    )
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val view = buildView(readModelFor(server))
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "partial runtime snapshot rendered") {
                E2eFx.hasText(view, "Warming up") && E2eFx.hasText(view, "Status updated")
            }

            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Degraded"), "overall pill degraded")
                assertTrue(
                    E2eFx.hasText(view, "AI stack is partially available"),
                    "degraded overall reason rendered"
                )
                assertTrue(E2eFx.hasText(view, "Down"), "LLM/embedding down pill rendered")
                assertTrue(E2eFx.hasText(view, "Disabled"), "memory disabled pill rendered")
                assertTrue(E2eFx.hasText(view, "Unknown"), "GPU unknown state rendered")
                assertTrue(E2eFx.hasText(view, "Not resolved"), "blank stack id shows Not resolved")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `runtime forbidden falls back to the health snapshot path`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.endsWith("/api/v1/llm/runtime") -> MockResponse().setResponseCode(403).setBody("forbidden")
                    path.endsWith("/api/v1/llm/health") -> json(
                        """
                        {
                          "status": "degraded",
                          "lifecycle_state": "WARMING_UP",
                          "lifecycle_reason": "warming",
                          "warmup_complete": false,
                          "llm_server_available": true,
                          "memory_available": false,
                          "memory_enabled": true,
                          "active_inferences": 2,
                          "queue_depth": 1,
                          "effective_provider": "llama.cpp",
                          "configured_model": "qwen3-14b",
                          "effective_model": "qwen3-14b-instruct"
                        }
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val view = buildView(readModelFor(server))
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "health-path snapshot rendered") {
                E2eFx.hasText(view, "from /health") && E2eFx.hasText(view, "Status updated")
            }

            E2eFx.onFx {
                assertTrue(
                    E2eFx.hasText(view, "require service authentication"),
                    "403 runtime surfaces the auth diagnostics note"
                )
                assertTrue(E2eFx.hasText(view, "qwen3-14b-instruct"), "effective model from health rendered")
                assertTrue(E2eFx.hasText(view, "WARMING_UP"), "lifecycle state from health rendered")
                assertTrue(E2eFx.hasText(view, "Degraded"), "overall degraded from health rendered")
            }
        } finally {
            server.shutdown()
        }
    }
}
