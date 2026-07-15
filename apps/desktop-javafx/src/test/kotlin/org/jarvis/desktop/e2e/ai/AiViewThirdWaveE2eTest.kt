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
 * THIRD-WAVE headless-UI E2E journeys for [AiView], targeting the render
 * branches NOT exercised by [AiViewE2eTest] (which covers a fully-ready
 * runtime, a partial/warming runtime, and the 403 → /health fallback):
 *
 *  - overall ERROR status (unrecognised top status + LLM unavailable), the LLM
 *    "Disabled" pill (enabled=false) and its "Not configured" blank-endpoint
 *    fallback, a memory card that is "Down" (enabled + serviceEnabled but not
 *    available), a GPU that is "Unavailable" with a blank device ("Not detected")
 *    and no hardware string ("No GPU hardware detected"), and the "Starting"
 *    lifecycle pill
 *  - overall DISABLED status, the "Verified" GPU pill (readiness verified while
 *    available=false), and the "Degraded" lifecycle pill (state != READY but usable)
 *  - the "CPU only" GPU pill (device=cpu → StatusLevel.DISABLED) and the else
 *    branch of the lifecycle pill (an arbitrary state → title-cased, here "Stopped")
 *
 * The lifecycle Start/Stop/Restart buttons are NOT fired — on a local runtime
 * they shell out via ProcessBuilder; the refresh path already drives every
 * `render*` branch. [AiView] does no I/O in its constructor, so we point the
 * read model at a [MockWebServer] and invoke `onRouteActivated()` as the shell does.
 */
class AiViewThirdWaveE2eTest {

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun readModelFor(server: MockWebServer): AiReadModel {
        val base = server.url("/").toString().removeSuffix("/")
        return AiReadModel(
            launcherSettingsProvider = { LauncherSettings() },
            llmServiceBaseUrl = base,
            memoryServiceBaseUrl = base,
            tokenProvider = { null }
        )
    }

    private fun runtimeDispatcher(runtimeBody: String): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path ?: ""
            return if (path.endsWith("/api/v1/llm/runtime")) json(runtimeBody)
            else MockResponse().setResponseCode(404)
        }
    }

    @Test
    fun `error runtime renders disabled llm, down memory, unavailable gpu and starting lifecycle`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.dispatcher = runtimeDispatcher(
            """
            {
              "status": "error",
              "llm": {"available": false, "enabled": false, "status": "disabled",
                      "reason": "llm off", "effectiveProvider": "none", "baseUrl": "", "device": ""},
              "memory": {"enabled": true, "serviceEnabled": true, "available": false,
                         "status": "down", "reason": "connection refused"},
              "embedding": {"available": false, "configuredModel": "bge-small", "reason": "not ready"},
              "gpu": {"available": false, "readinessStatus": "failed", "readinessReason": "no cuda"},
              "localDefaultStack": {"id": "stack-err"},
              "lifecycle": {"state": "STARTING", "reason": "booting", "warmup_complete": false, "usable": false},
              "admission": {"active_inferences": 0, "queue_depth": 0, "total_admitted": 0, "rejected_count": 0, "available_permits": 0}
            }
            """.trimIndent()
        )
        server.start()
        try {
            val view = E2eFx.onFx { AiView(readModelFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "error runtime snapshot rendered") {
                E2eFx.hasText(view, "AI services have errors") && E2eFx.hasText(view, "Status updated")
            }

            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Error"), "overall ERROR pill rendered")
                assertTrue(E2eFx.hasText(view, "Disabled"), "LLM disabled pill rendered")
                assertTrue(E2eFx.hasText(view, "Not configured"), "blank LLM endpoint falls back to Not configured")
                assertTrue(E2eFx.hasText(view, "Down"), "memory Down pill rendered")
                assertTrue(E2eFx.hasText(view, "Unavailable"), "GPU Unavailable pill rendered")
                assertTrue(E2eFx.hasText(view, "Not detected"), "blank GPU device falls back to Not detected")
                assertTrue(
                    E2eFx.hasText(view, "No GPU hardware detected"),
                    "empty GPU hardware string rendered for a non-unknown status"
                )
                assertTrue(E2eFx.hasText(view, "Starting"), "STARTING lifecycle pill rendered")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `disabled runtime renders a verified gpu and a degraded lifecycle`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.dispatcher = runtimeDispatcher(
            """
            {
              "status": "disabled",
              "llm": {"available": false, "enabled": true, "status": "down", "reason": "not up",
                      "effectiveProvider": "llama.cpp", "baseUrl": "http://127.0.0.1:8091", "device": "cuda:0"},
              "memory": {"enabled": true, "serviceEnabled": true, "available": true, "status": "ready", "reason": ""},
              "embedding": {"available": true, "configuredModel": "bge-small", "effectiveModel": "bge-small", "dimension": 384, "reason": ""},
              "gpu": {"available": false, "effectiveDevicePath": "cuda:0", "readinessStatus": "verified",
                      "readinessReason": "cuda ok", "gpuName": "RTX 5070"},
              "localDefaultStack": {"id": "local-cuda"},
              "lifecycle": {"state": "DEGRADED", "reason": "partial", "warmup_complete": true, "usable": true},
              "admission": {"active_inferences": 0, "queue_depth": 0, "total_admitted": 0, "rejected_count": 0, "available_permits": 4}
            }
            """.trimIndent()
        )
        server.start()
        try {
            val view = E2eFx.onFx { AiView(readModelFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "disabled runtime snapshot rendered") {
                E2eFx.hasText(view, "AI services are disabled in configuration") && E2eFx.hasText(view, "Status updated")
            }

            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "Verified"), "readiness-verified GPU pill rendered while available=false")
                assertTrue(E2eFx.hasText(view, "Degraded"), "usable-but-not-ready lifecycle pill rendered as Degraded")
                assertTrue(E2eFx.hasText(view, "RTX 5070"), "GPU hardware name rendered")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `cpu-only gpu and an arbitrary lifecycle state render CPU-only and a title-cased pill`() {
        assumeTrue(E2eFx.toolkitAvailable(), "JavaFX toolkit unavailable — skipping")
        val server = MockWebServer()
        server.dispatcher = runtimeDispatcher(
            """
            {
              "status": "degraded",
              "llm": {"available": true, "enabled": true, "status": "ready", "reason": "",
                      "effectiveProvider": "llama.cpp", "baseUrl": "http://127.0.0.1:8091", "device": "cpu"},
              "memory": {"enabled": false, "serviceEnabled": false, "available": false, "status": "disabled", "reason": "off"},
              "embedding": {"available": true, "configuredModel": "bge-small", "effectiveModel": "bge-small", "dimension": 384, "reason": ""},
              "gpu": {"available": false, "effectiveDevicePath": "cpu", "readinessStatus": "disabled",
                      "readinessReason": "cpu mode", "gpuName": ""},
              "localDefaultStack": {"id": "local-cpu"},
              "lifecycle": {"state": "STOPPED", "reason": "halted", "warmup_complete": false, "usable": false},
              "admission": {"active_inferences": 0, "queue_depth": 0, "total_admitted": 0, "rejected_count": 0, "available_permits": 0}
            }
            """.trimIndent()
        )
        server.start()
        try {
            val view = E2eFx.onFx { AiView(readModelFor(server)) }
            E2eFx.onFx { view.onRouteActivated() }

            E2eFx.waitForFx(description = "cpu-only runtime snapshot rendered") {
                E2eFx.hasText(view, "CPU only") && E2eFx.hasText(view, "Status updated")
            }

            E2eFx.onFx {
                assertTrue(E2eFx.hasText(view, "CPU only"), "device=cpu maps to the CPU only GPU pill")
                assertTrue(
                    E2eFx.hasText(view, "Stopped"),
                    "an arbitrary lifecycle state title-cases into the else-branch pill: ${E2eFx.visibleText(view)}"
                )
            }
        } finally {
            server.shutdown()
        }
    }
}
