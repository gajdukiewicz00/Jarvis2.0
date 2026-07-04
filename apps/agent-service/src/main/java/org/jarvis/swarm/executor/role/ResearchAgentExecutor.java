package org.jarvis.swarm.executor.role;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.executor.ExecutionContext;
import org.jarvis.swarm.executor.RoleExecutor;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.sandbox.SandboxManager;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * RESEARCH: builds a research plan and structured notes in its sandbox. It does NOT reach
 * the internet by default — only if NETWORK_ACCESS is explicitly requested and granted
 * (and even then, live fetching is intentionally not wired in this MVP, so it stays
 * offline and records that fetching would require enablement).
 */
@Component
public class ResearchAgentExecutor implements RoleExecutor {

    private final SandboxManager sandbox;

    public ResearchAgentExecutor(SandboxManager sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public AgentRole role() {
        return AgentRole.RESEARCH;
    }

    @Override
    public RoleResult execute(ExecutionContext ctx) {
        ctx.checkpoint();
        boolean wantsNetwork = ctx.task().permissionsRequested().contains(ToolPermission.NETWORK_ACCESS);
        boolean networkAllowed = false;
        if (wantsNetwork) {
            // Enforce the grant; a denied network request rejects the task.
            ctx.guard().ensurePermission(ctx.task(), ToolPermission.NETWORK_ACCESS);
            networkAllowed = true;
        }

        String goal = ctx.task().goal();
        String notes = render(goal, networkAllowed);
        List<String> proposed = List.of("define questions", "gather sources", "synthesize notes");
        List<String> next = networkAllowed
                ? List.of("Live fetching is not wired in this MVP; notes are offline")
                : List.of("Grant NETWORK_ACCESS to enable online research");

        if (ctx.dryRun() || ctx.sandbox() == null) {
            return RoleResult.success("RESEARCH plan (dry-run) for: " + goal, notes, List.of(), proposed,
                    List.of(), next);
        }
        Path file = sandbox.writeFile(ctx.sandbox(), "research/NOTES.md", notes);
        return RoleResult.success("RESEARCH produced structured notes in sandbox", notes,
                List.of(file.toString()), proposed, List.of(), next);
    }

    private String render(String goal, boolean networkAllowed) {
        return "# Research Notes\n\nGoal: " + goal + "\n\n## Key questions\n"
                + "1. What is the current state?\n2. What approaches exist?\n3. What are the trade-offs?\n\n"
                + "## Mode\n" + (networkAllowed ? "Online research permitted (offline in MVP)." : "Offline only.")
                + "\n";
    }
}
