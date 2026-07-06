package org.jarvis.swarm.web;

import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.task.AgentTask;

import java.util.List;

/**
 * Renders a single agent task's combined state (summary, risks, artifacts, captured
 * output) as a downloadable markdown report — the "combined report" artifact for a task,
 * distinct from the raw {@code DIFF.patch} download. Pure formatting, no I/O.
 */
public final class TaskReportRenderer {

    private TaskReportRenderer() {
    }

    public static String render(AgentTask task, RoleResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Agent Task Report\n\n");
        sb.append("- Task ID: ").append(task.taskId()).append('\n');
        sb.append("- Role: ").append(task.role()).append('\n');
        sb.append("- Status: ").append(task.status()).append('\n');
        sb.append("- Goal: ").append(task.goal()).append('\n');
        sb.append("- Dry run: ").append(task.dryRun()).append('\n');
        sb.append('\n');

        String summary = task.resultSummary() != null ? task.resultSummary()
                : task.errorMessage() != null ? task.errorMessage() : task.status().name();
        sb.append("## Summary\n\n").append(summary).append("\n\n");

        List<String> risks = result != null && !result.risks().isEmpty() ? result.risks() : task.risks();
        if (!risks.isEmpty()) {
            sb.append("## Risks\n\n");
            risks.forEach(r -> sb.append("- ").append(r).append('\n'));
            sb.append('\n');
        }

        if (!task.artifacts().isEmpty()) {
            sb.append("## Artifacts\n\n");
            task.artifacts().forEach(a -> sb.append("- ").append(a).append('\n'));
            sb.append('\n');
        }

        if (result != null && result.output() != null && !result.output().isBlank()) {
            sb.append("## Output\n\n```\n").append(result.output()).append("\n```\n");
        }
        return sb.toString();
    }
}
