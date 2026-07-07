package org.jarvis.desktop.features.planner

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Locale

class PlannerReadModelTest {

    private fun baseUrlFor(server: MockWebServer): String = server.url("/").toString().removeSuffix("/")

    private fun configFor(server: MockWebServer): () -> ResolvedDesktopConfig {
        val baseUrl = baseUrlFor(server)
        return {
            ResolvedDesktopConfig(
                apiGatewayBaseUrl = baseUrl,
                apiBaseUrl = "$baseUrl/api/v1",
                voiceWebSocketUrl = "$baseUrl/ws/voice",
                pcControlWebSocketUrl = "$baseUrl/ws/pc-control",
                locale = Locale.ENGLISH,
                voiceLanguage = "en-US",
                apiGatewaySource = ConfigSource.MANUAL_PERSISTED_SETTINGS,
                apiGatewayReason = "test",
                usesManualEndpointOverride = true
            )
        }
    }

    /** Points the PATCH-only client at the same MockWebServer, bypassing the real AppConfig/TokenManager singletons. */
    private fun patchClientFor(server: MockWebServer): PlannerPatchClient =
        PlannerPatchClient(baseUrlProvider = { "${baseUrlFor(server)}/api/v1" }, tokenProvider = { null })

    private fun modelFor(server: MockWebServer) =
        PlannerReadModel(ApiClient(configFor(server)), patchClientFor(server))

    @Test
    fun `loadSnapshot parses recurring template and occurrence markers`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {"id": 1, "title": "Water plants", "priority": "LOW", "status": "TODO",
                       "recurrenceRule": "DAILY", "recurrenceSourceTaskId": null, "tags": []},
                      {"id": 2, "title": "Water plants (today)", "priority": "LOW", "status": "TODO",
                       "recurrenceRule": "NONE", "recurrenceSourceTaskId": 1, "tags": []},
                      {"id": 3, "title": "One-off task", "priority": "MEDIUM", "status": "TODO",
                       "recurrenceRule": null, "recurrenceSourceTaskId": null, "tags": []}
                    ]
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val snapshot = modelFor(server).loadSnapshot()
            val byId = snapshot.tasks.associateBy(PlannerReadModel.TodoTask::id)

            assertTrue(byId.getValue(1).isRecurringTemplate)
            assertFalse(byId.getValue(1).isRecurringOccurrence)

            assertFalse(byId.getValue(2).isRecurringTemplate)
            assertTrue(byId.getValue(2).isRecurringOccurrence)

            assertFalse(byId.getValue(3).isRecurringTemplate)
            assertFalse(byId.getValue(3).isRecurringOccurrence)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `createTodo sends the due date when provided`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 5, "title": "Renew passport", "priority": "HIGH", "status": "TODO", "tags": []}""")
        )

        try {
            server.start()
            val dueDate = Instant.parse("2026-08-01T00:00:00Z")
            val created = modelFor(server).createTodo("Renew passport", null, "HIGH", dueDate)

            assertEquals(5L, created.id)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/tools/todo/create"))
            assertTrue(request.body.readUtf8().contains("2026-08-01"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `updateTodo posts title priority and due date to tools todo update`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 9, "title": "Renamed", "priority": "URGENT", "status": "TODO", "tags": []}""")
        )

        try {
            server.start()
            val dueDate = Instant.parse("2026-09-01T00:00:00Z")
            val updated = modelFor(server).updateTodo(9, "Renamed", "new description", "URGENT", dueDate)

            assertEquals("Renamed", updated.title)
            assertEquals("URGENT", updated.priority)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/tools/todo/update"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"id\":9"))
            assertTrue(body.contains("Renamed"))
            assertTrue(body.contains("URGENT"))
            assertTrue(body.contains("2026-09-01"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `deleteTodo issues a DELETE against the planner tasks path`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(204))

        try {
            server.start()
            modelFor(server).deleteTodo(42)

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertTrue(request.path!!.contains("/planner/tasks/42"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadPlanMode reads back the persisted mode`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"mode": "DEEP_WORK"}""")
        )

        try {
            server.start()
            assertEquals("DEEP_WORK", modelFor(server).loadPlanMode())

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertTrue(request.path!!.contains("/planner/plan/mode"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadPlanMode defaults to NORMAL when the endpoint is unavailable`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))

        try {
            server.start()
            assertEquals("NORMAL", modelFor(server).loadPlanMode())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `setPlanMode posts the selected mode and returns the saved value`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"mode": "RECOVERY"}""")
        )

        try {
            server.start()
            val saved = modelFor(server).setPlanMode("RECOVERY")

            assertEquals("RECOVERY", saved)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/planner/plan/mode"))
            assertTrue(request.body.readUtf8().contains("RECOVERY"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadPlanByMode summarizes the ranked task list`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "mode": "DEEP_WORK", "energy": "HIGH",
                      "tasks": [
                        {"taskId": 1, "title": "Write the report", "priority": "HIGH", "deadlinePressure": "OVERDUE"}
                      ]
                    }
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val result = modelFor(server).loadPlanByMode()

            assertTrue(result is PlannerReadModel.BriefResult.Available)
            val text = (result as PlannerReadModel.BriefResult.Available).text
            assertTrue(text.contains("DEEP_WORK"))
            assertTrue(text.contains("Write the report"))
            assertTrue(text.contains("OVERDUE"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadPlanByMode degrades gracefully when the endpoint fails`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            server.start()
            val result = modelFor(server).loadPlanByMode()

            assertTrue(result is PlannerReadModel.BriefResult.Unavailable)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `skipOccurrence PATCHes the skip-occurrence path`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id": 11, "title": "Occurrence", "priority": "LOW", "status": "SKIPPED",
                        "recurrenceSourceTaskId": 1, "tags": []}"""
                )
        )

        try {
            server.start()
            val occurrence = modelFor(server).skipOccurrence(11)

            assertEquals("SKIPPED", occurrence.status)
            assertTrue(occurrence.isRecurringOccurrence)

            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertTrue(request.path!!.contains("/planner/tasks/11/skip-occurrence"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `completeOccurrence PATCHes the complete-occurrence path`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id": 12, "title": "Occurrence", "priority": "LOW", "status": "DONE",
                        "recurrenceSourceTaskId": 1, "tags": []}"""
                )
        )

        try {
            server.start()
            val occurrence = modelFor(server).completeOccurrence(12)

            assertEquals("DONE", occurrence.status)

            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertTrue(request.path!!.contains("/planner/tasks/12/complete-occurrence"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `generateNextOccurrences posts with the requested count and parses the generated list`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {"id": 20, "title": "Occurrence 1", "priority": "LOW", "status": "TODO",
                       "recurrenceSourceTaskId": 1, "tags": []},
                      {"id": 21, "title": "Occurrence 2", "priority": "LOW", "status": "TODO",
                       "recurrenceSourceTaskId": 1, "tags": []}
                    ]
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val generated = modelFor(server).generateNextOccurrences(1, count = 2)

            assertEquals(2, generated.size)
            assertTrue(generated.all { it.isRecurringOccurrence })

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/planner/tasks/1/generate-next-occurrences"))
            assertTrue(request.path!!.contains("count=2"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `parseTask treats a NONE recurrence rule as not a template`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """[{"id": 30, "title": "Plain task", "priority": "MEDIUM", "status": "TODO",
                        "recurrenceRule": "NONE", "tags": []}]"""
                )
        )

        try {
            server.start()
            val task = modelFor(server).loadSnapshot().tasks.single()

            assertFalse(task.isRecurringTemplate)
            assertFalse(task.isRecurringOccurrence)
            assertNull(task.recurrenceSourceTaskId)
        } finally {
            server.shutdown()
        }
    }
}
