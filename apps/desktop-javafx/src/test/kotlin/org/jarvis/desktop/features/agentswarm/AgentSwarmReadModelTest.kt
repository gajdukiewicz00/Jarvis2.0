package org.jarvis.desktop.features.agentswarm

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class AgentSwarmReadModelTest {

    private fun configFor(server: MockWebServer): () -> ResolvedDesktopConfig {
        val baseUrl = server.url("/").toString().removeSuffix("/")
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

    private fun modelFor(server: MockWebServer) = AgentSwarmReadModel(ApiClient(configFor(server)))

    @Test
    fun `listRoles parses the role catalog`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {"role": "CODER", "displayName": "Coder", "description": "Writes patches", "sandboxRequired": true},
                      {"role": "DOCS", "displayName": "Docs", "description": "Writes docs", "sandboxRequired": false}
                    ]
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val roles = modelFor(server).listRoles()

            assertEquals(2, roles.size)
            assertEquals("CODER", roles[0].role)
            assertEquals("Writes patches", roles[0].description)
            assertTrue(roles[0].sandboxRequired)
            assertEquals("DOCS", roles[1].role)

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("/agents/roles"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `listTasks parses task views including terminal state`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {"taskId": "t-1", "role": "CODER", "goal": "fix bug", "status": "RUNNING", "dryRun": true,
                       "swarmId": "s-1", "resultSummary": null, "errorMessage": null, "risks": ["may touch prod config"]},
                      {"taskId": "t-2", "role": "TESTER", "goal": "run tests", "status": "COMPLETED", "dryRun": false,
                       "resultSummary": "42 passed"}
                    ]
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val tasks = modelFor(server).listTasks()

            assertEquals(2, tasks.size)
            assertEquals("t-1", tasks[0].taskId)
            assertEquals("RUNNING", tasks[0].status)
            assertTrue(tasks[0].dryRun)
            assertEquals(listOf("may touch prod config"), tasks[0].risks)
            assertTrue(!tasks[0].isTerminal)

            assertEquals("42 passed", tasks[1].resultSummary)
            assertTrue(tasks[1].isTerminal)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `cancelTask posts to the cancel endpoint and parses the resulting task`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"taskId": "t-1", "role": "CODER", "goal": "fix bug", "status": "CANCELLED", "dryRun": true}""")
        )

        try {
            server.start()
            val task = modelFor(server).cancelTask("t-1")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/agents/tasks/t-1/cancel"))
            assertEquals("CANCELLED", task.status)
            assertTrue(task.isTerminal)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `startDryRunSwarm always sends dryRun true and awaitCompletion false`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"swarmId": "s-9", "goal": "goal", "roles": ["CODER", "TESTER"], "taskIds": ["t-1", "t-2"], "dryRun": true}""")
        )

        try {
            server.start()
            val started = modelFor(server).startDryRunSwarm("goal", listOf("CODER", "TESTER"))

            assertEquals("s-9", started.swarmId)
            assertEquals(listOf("CODER", "TESTER"), started.roles)
            assertTrue(started.dryRun)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/agents/swarm"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"dryRun\":true"))
            assertTrue(body.contains("\"awaitCompletion\":false"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `report parses the combined report including per-role outcomes and risks`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "swarmId": "s-9", "goal": "goal", "complete": true,
                      "rolesUsed": ["CODER", "TESTER"],
                      "perRole": [{"role": "CODER", "taskId": "t-1", "status": "COMPLETED", "summary": "proposed patch", "output": "diff", "artifacts": [], "risks": []}],
                      "failedRoles": ["TESTER"],
                      "risks": ["patch is unreviewed"],
                      "nextActions": ["run tests"]
                    }
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val report = modelFor(server).report("s-9")

            assertEquals("s-9", report.swarmId)
            assertTrue(report.complete)
            assertEquals(listOf("CODER", "TESTER"), report.rolesUsed)
            assertEquals(1, report.perRole.size)
            assertEquals("CODER", report.perRole[0].role)
            assertEquals("proposed patch", report.perRole[0].summary)
            assertEquals(listOf("TESTER"), report.failedRoles)
            assertEquals(listOf("patch is unreviewed"), report.risks)
            assertEquals(listOf("run tests"), report.nextActions)

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("/agents/swarm/s-9"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `isAwaitingApproval is true only for AWAITING_APPROVAL status`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {"taskId": "t-1", "role": "CODER", "goal": "fix bug", "status": "AWAITING_APPROVAL", "dryRun": false},
                      {"taskId": "t-2", "role": "CODER", "goal": "fix bug", "status": "RUNNING", "dryRun": false}
                    ]
                    """.trimIndent()
                )
        )

        try {
            server.start()
            val tasks = modelFor(server).listTasks()

            assertTrue(tasks[0].isAwaitingApproval)
            assertTrue(!tasks[1].isAwaitingApproval)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `approveTask posts to the approve endpoint and parses the resulting task`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"taskId": "t-1", "role": "CODER", "goal": "fix bug", "status": "COMPLETED", "dryRun": false}""")
        )

        try {
            server.start()
            val task = modelFor(server).approveTask("t-1")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/agents/tasks/t-1/approve"))
            assertEquals("COMPLETED", task.status)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `rejectTask posts to the reject endpoint and parses the resulting task`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"taskId": "t-1", "role": "CODER", "goal": "fix bug", "status": "CANCELLED", "dryRun": false}""")
        )

        try {
            server.start()
            val task = modelFor(server).rejectTask("t-1")

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/agents/tasks/t-1/reject"))
            assertEquals("CANCELLED", task.status)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `submitTask posts to the tasks endpoint with dryRun false and the given approvalRequired flag`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"taskId": "t-7", "role": "CODER", "goal": "fix bug", "status": "AWAITING_APPROVAL", "dryRun": false}"""
                )
        )

        try {
            server.start()
            val task = modelFor(server).submitTask("CODER", "fix bug", approvalRequired = true)

            assertEquals("t-7", task.taskId)
            assertEquals("CODER", task.role)
            assertEquals("AWAITING_APPROVAL", task.status)
            assertTrue(task.isAwaitingApproval)

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.path!!.contains("/agents/tasks"))
            assertTrue(!request.path!!.contains("/agents/tasks/"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"role\":\"CODER\""))
            assertTrue(body.contains("\"goal\":\"fix bug\""))
            assertTrue(body.contains("\"dryRun\":false"))
            assertTrue(body.contains("\"approvalRequired\":true"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `downloadDiff GETs the diff artifact path and returns its raw text`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("--- a/file\n+++ b/file\n"))

        try {
            server.start()
            val diff = modelFor(server).downloadDiff("t-1")

            assertTrue(diff.contains("--- a/file"))

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertTrue(request.path!!.contains("/agents/tasks/t-1/artifacts/diff"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `downloadReport GETs the report artifact path and returns its raw text`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("# Task Report\n"))

        try {
            server.start()
            val report = modelFor(server).downloadReport("t-1")

            assertTrue(report.contains("# Task Report"))

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertTrue(request.path!!.contains("/agents/tasks/t-1/artifacts/report"))
        } finally {
            server.shutdown()
        }
    }
}
