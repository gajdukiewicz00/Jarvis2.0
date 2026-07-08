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
import java.time.LocalDate
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

    @Test
    fun `completeTodo posts the task id and parses the completed task`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 7, "title": "Ship report", "priority": "HIGH", "status": "DONE", "tags": []}""")
        )

        try {
            server.start()
            val completed = modelFor(server).completeTodo(7)

            assertEquals("DONE", completed.status)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/tools/todo/complete"))
            assertTrue(request.body.readUtf8().contains("\"id\":7"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadFocus returns the direct focus field when present`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"focus": "Finish the report"}""")
        )

        try {
            server.start()
            val result = modelFor(server).loadFocus()

            assertTrue(result is PlannerReadModel.BriefResult.Available)
            assertEquals("Finish the report", (result as PlannerReadModel.BriefResult.Available).text)

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("/planner/focus"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadEveningReview formats a highlights list of objects when no direct field is present`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"highlights": [{"title": "Completed 3 tasks"}, {"title": "Skipped gym"}]}"""
                )
        )

        try {
            server.start()
            val result = modelFor(server).loadEveningReview()

            assertTrue(result is PlannerReadModel.BriefResult.Available)
            val text = (result as PlannerReadModel.BriefResult.Available).text
            assertEquals("• Completed 3 tasks\n• Skipped gym", text)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadEveningReview formats a plain string items list`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"items": ["one", "two"]}""")
        )

        try {
            server.start()
            val result = modelFor(server).loadEveningReview()

            assertTrue(result is PlannerReadModel.BriefResult.Available)
            assertEquals("• one\n• two", (result as PlannerReadModel.BriefResult.Available).text)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadFocus falls back to the raw response body when it is not JSON`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/plain").setBody("Not valid json at all")
        )

        try {
            server.start()
            val result = modelFor(server).loadFocus()

            assertTrue(result is PlannerReadModel.BriefResult.Available)
            assertEquals("Not valid json at all", (result as PlannerReadModel.BriefResult.Available).text)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadFocus degrades gracefully when the endpoint fails`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))

        try {
            server.start()
            val result = modelFor(server).loadFocus()

            assertTrue(result is PlannerReadModel.BriefResult.Unavailable)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadWeeklyPlan formats each day's task titles in order`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"days": {"monday": ["Standup", "Write report"], "tuesday": []}}"""
                )
        )

        try {
            server.start()
            val result = modelFor(server).loadWeeklyPlan()

            assertTrue(result is PlannerReadModel.BriefResult.Available)
            val text = (result as PlannerReadModel.BriefResult.Available).text
            assertTrue(text.contains("Monday: Standup, Write report"))
            assertTrue(text.contains("Tuesday:"))

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("/planner/weekly"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadWeeklyPlan reports no breakdown when there are zero total tasks`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"totalTasks": 0}""")
        )

        try {
            server.start()
            val result = modelFor(server).loadWeeklyPlan()

            assertTrue(result is PlannerReadModel.BriefResult.Available)
            assertEquals(
                "No tasks distributed across this week yet.",
                (result as PlannerReadModel.BriefResult.Available).text
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadWeeklyPlan reports the total task count when the day breakdown is unavailable`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"totalTasks": 7}""")
        )

        try {
            server.start()
            val result = modelFor(server).loadWeeklyPlan()

            assertTrue(result is PlannerReadModel.BriefResult.Available)
            val text = (result as PlannerReadModel.BriefResult.Available).text
            assertTrue(text.contains("7 task(s) queued"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadWeeklyPlan degrades gracefully when the endpoint fails`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            server.start()
            assertTrue(modelFor(server).loadWeeklyPlan() is PlannerReadModel.BriefResult.Unavailable)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadTomorrowPlan formats the focus goal, non-empty blocks, and tasks for the day`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "focusGoal": "Ship the release",
                      "blocks": {"morning": ["Standup", "Code review"], "work": ["Deploy"], "evening": []},
                      "tasksForDay": [{"title": "Deploy v2"}, {"title": "Write changelog"}]
                    }
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val tomorrow = LocalDate.now().plusDays(1)
            val result = modelFor(server).loadTomorrowPlan()

            assertTrue(result is PlannerReadModel.BriefResult.Available)
            val text = (result as PlannerReadModel.BriefResult.Available).text
            assertTrue(text.contains("Focus: Ship the release"))
            assertTrue(text.contains("Morning: Standup; Code review"))
            assertTrue(text.contains("Work: Deploy"))
            assertFalse(text.contains("Evening:"))
            assertTrue(text.contains("Tasks: Deploy v2, Write changelog"))

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("/planner/daily?date=$tomorrow"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadTomorrowPlan reports no plan details when the payload is empty`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{}"""))

        try {
            server.start()
            val result = modelFor(server).loadTomorrowPlan()

            assertTrue(result is PlannerReadModel.BriefResult.Available)
            assertEquals(
                "No plan details available yet.",
                (result as PlannerReadModel.BriefResult.Available).text
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `loadTomorrowPlan degrades gracefully when the endpoint fails`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            server.start()
            assertTrue(modelFor(server).loadTomorrowPlan() is PlannerReadModel.BriefResult.Unavailable)
        } finally {
            server.shutdown()
        }
    }
}
