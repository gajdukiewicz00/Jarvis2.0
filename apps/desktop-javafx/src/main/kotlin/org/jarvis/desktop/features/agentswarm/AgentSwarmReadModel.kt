package org.jarvis.desktop.features.agentswarm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jarvis.desktop.api.ApiClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Read model for the Agent Swarm panel.
 *
 * Wires the multi-role "House Party Protocol" swarm surface exposed by the
 * agent-service (roadmap wave-1):
 *  - role catalog  -> GET  /api/v1/agents/roles
 *  - task list     -> GET  /api/v1/agents/tasks
 *  - cancel a task -> POST /api/v1/agents/tasks/{id}/cancel
 *  - start a swarm -> POST /api/v1/agents/swarm          (this panel always sends dryRun=true)
 *  - combined report -> GET /api/v1/agents/swarm/{id}
 *  - approve a pending CODER patch (AWAITING_APPROVAL) -> POST /api/v1/agents/tasks/{id}/approve
 *  - reject a pending CODER patch                      -> POST /api/v1/agents/tasks/{id}/reject
 *  - download the produced diff                         -> GET  /api/v1/agents/tasks/{id}/artifacts/diff
 *  - download the rendered task report                  -> GET  /api/v1/agents/tasks/{id}/artifacts/report
 *
 * Response shapes mirror `org.jarvis.swarm.role.RoleDefinition`,
 * `org.jarvis.swarm.web.TaskView`, `org.jarvis.swarm.run.SwarmRun`, and
 * `org.jarvis.swarm.run.CombinedReport` on the agent-service side.
 */
class AgentSwarmReadModel(
    private val apiClient: ApiClient
) {
    private val objectMapper = jacksonObjectMapper()

    data class RoleInfo(
        val role: String,
        val description: String,
        val sandboxRequired: Boolean
    )

    data class TaskInfo(
        val taskId: String,
        val role: String,
        val goal: String,
        val status: String,
        val dryRun: Boolean,
        val swarmId: String?,
        val resultSummary: String?,
        val errorMessage: String?,
        val risks: List<String>
    ) {
        /** COMPLETED/CANCELLED are the only terminal states — see AgentTaskStatus.isTerminal(). */
        val isTerminal: Boolean
            get() = status == "COMPLETED" || status == "CANCELLED"

        /** A pending CODER patch proposal is waiting for an explicit approve/reject. */
        val isAwaitingApproval: Boolean
            get() = status == "AWAITING_APPROVAL"
    }

    data class RoleOutcomeInfo(
        val role: String,
        val status: String,
        val summary: String
    )

    data class CombinedReportInfo(
        val swarmId: String,
        val goal: String,
        val complete: Boolean,
        val rolesUsed: List<String>,
        val perRole: List<RoleOutcomeInfo>,
        val failedRoles: List<String>,
        val risks: List<String>,
        val nextActions: List<String>
    )

    data class SwarmStarted(val swarmId: String, val roles: List<String>, val dryRun: Boolean)

    fun listRoles(): List<RoleInfo> {
        val root = objectMapper.readTree(apiClient.get("/agents/roles"))
        if (!root.isArray) return emptyList()
        return root.map { node ->
            RoleInfo(
                role = node.path("role").asText(""),
                description = node.path("description").asText(""),
                sandboxRequired = node.path("sandboxRequired").asBoolean(false)
            )
        }
    }

    fun listTasks(): List<TaskInfo> {
        val root = objectMapper.readTree(apiClient.get("/agents/tasks"))
        if (!root.isArray) return emptyList()
        return root.map(::parseTask)
    }

    fun cancelTask(taskId: String): TaskInfo =
        parseTask(objectMapper.readTree(apiClient.post("/agents/tasks/${encode(taskId)}/cancel", "{}")))

    /** Approve a pending CODER patch proposal (AWAITING_APPROVAL): applies it to the sandbox. */
    fun approveTask(taskId: String): TaskInfo =
        parseTask(objectMapper.readTree(apiClient.post("/agents/tasks/${encode(taskId)}/approve", "{}")))

    /** Reject a pending CODER patch proposal: discards it, nothing is ever applied. */
    fun rejectTask(taskId: String): TaskInfo =
        parseTask(objectMapper.readTree(apiClient.post("/agents/tasks/${encode(taskId)}/reject", "{}")))

    /** Downloads the CODER-produced DIFF.patch for a task as text. */
    fun downloadDiff(taskId: String): String =
        apiClient.get("/agents/tasks/${encode(taskId)}/artifacts/diff")

    /** Downloads the rendered combined report (summary, risks, artifacts, output) for a task. */
    fun downloadReport(taskId: String): String =
        apiClient.get("/agents/tasks/${encode(taskId)}/artifacts/report")

    /**
     * Starts a swarm run. Always sends `dryRun=true` and `awaitCompletion=false` so
     * triggering it from the GUI never performs side effects and never blocks the
     * calling thread waiting on the (potentially slow) full run.
     */
    fun startDryRunSwarm(goal: String, roles: List<String>): SwarmStarted {
        val payload = objectMapper.createObjectNode().apply {
            put("goal", goal.trim())
            putArray("roles").apply { roles.forEach { add(it) } }
            putArray("permissions")
            put("dryRun", true)
            put("awaitCompletion", false)
        }
        val root = objectMapper.readTree(apiClient.post("/agents/swarm", objectMapper.writeValueAsString(payload)))
        return SwarmStarted(
            swarmId = root.path("swarmId").asText(""),
            roles = root.path("roles").takeIf(JsonNode::isArray)?.map { it.asText() } ?: roles,
            dryRun = root.path("dryRun").let { !it.isBoolean || it.asBoolean() }
        )
    }

    fun report(swarmId: String): CombinedReportInfo =
        parseReport(objectMapper.readTree(apiClient.get("/agents/swarm/${encode(swarmId)}")))

    private fun parseTask(node: JsonNode): TaskInfo {
        return TaskInfo(
            taskId = node.path("taskId").asText(""),
            role = node.path("role").asText(""),
            goal = node.path("goal").asText(""),
            status = node.path("status").asText("UNKNOWN"),
            dryRun = node.path("dryRun").asBoolean(false),
            swarmId = node.path("swarmId").textOrNull(),
            resultSummary = node.path("resultSummary").textOrNull(),
            errorMessage = node.path("errorMessage").textOrNull(),
            risks = node.path("risks").takeIf(JsonNode::isArray)?.map { it.asText() } ?: emptyList()
        )
    }

    private fun parseReport(root: JsonNode): CombinedReportInfo {
        return CombinedReportInfo(
            swarmId = root.path("swarmId").asText(""),
            goal = root.path("goal").asText(""),
            complete = root.path("complete").asBoolean(false),
            rolesUsed = root.path("rolesUsed").takeIf(JsonNode::isArray)?.map { it.asText() } ?: emptyList(),
            perRole = root.path("perRole").takeIf(JsonNode::isArray)?.map { node ->
                RoleOutcomeInfo(
                    role = node.path("role").asText(""),
                    status = node.path("status").asText(""),
                    summary = node.path("summary").asText("")
                )
            } ?: emptyList(),
            failedRoles = root.path("failedRoles").takeIf(JsonNode::isArray)?.map { it.asText() } ?: emptyList(),
            risks = root.path("risks").takeIf(JsonNode::isArray)?.map { it.asText() } ?: emptyList(),
            nextActions = root.path("nextActions").takeIf(JsonNode::isArray)?.map { it.asText() } ?: emptyList()
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun JsonNode.textOrNull(): String? =
        if (isMissingNode || isNull) null else asText(null)?.takeIf { it.isNotBlank() }
}
