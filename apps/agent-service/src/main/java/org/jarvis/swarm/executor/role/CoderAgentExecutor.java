package org.jarvis.swarm.executor.role;

import org.jarvis.swarm.executor.ExecutionContext;
import org.jarvis.swarm.executor.RoleExecutor;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.sandbox.Sandbox;
import org.jarvis.swarm.sandbox.SandboxManager;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CODER: turns a goal into a concrete implementation plan and proposes file changes. In
 * dryRun it returns the planned files/actions without touching disk. Otherwise it writes
 * the plan and stub files INTO ITS SANDBOX (safe by construction). Applying changes to
 * the real repository would require WRITE_FILES and is intentionally never done here.
 */
@Component
public class CoderAgentExecutor implements RoleExecutor {

    private final SandboxManager sandbox;

    public CoderAgentExecutor(SandboxManager sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public AgentRole role() {
        return AgentRole.CODER;
    }

    @Override
    public RoleResult execute(ExecutionContext ctx) {
        ctx.checkpoint();
        String goal = ctx.task().goal();
        List<String> steps = planSteps(goal);
        List<String> files = proposeFiles(goal);

        List<String> proposed = new ArrayList<>();
        proposed.add("Implementation plan with " + steps.size() + " step(s)");
        files.forEach(f -> proposed.add("create " + f));
        String planText = renderPlan(goal, steps, files);

        List<String> next = List.of(
                "Review the plan before applying",
                "Applying to the real repository requires WRITE_FILES permission (not granted here)");

        if (ctx.dryRun() || ctx.sandbox() == null) {
            return RoleResult.success(
                    "CODER plan (dry-run): " + steps.size() + " steps, " + files.size() + " proposed files",
                    planText, List.of(), proposed, List.of(), next);
        }

        Sandbox sb = ctx.sandbox();
        List<String> artifacts = new ArrayList<>();
        Path plan = sandbox.writeFile(sb, "PLAN.md", planText);
        artifacts.add(plan.toString());
        for (String file : files) {
            ctx.checkpoint();
            Path stub = sandbox.writeFile(sb, "proposed/" + file,
                    "// Proposed by CODER agent for goal:\n// " + goal + "\n// TODO: implement\n");
            artifacts.add(stub.toString());
        }
        return RoleResult.success(
                "CODER produced a plan and " + files.size() + " stub file(s) in sandbox",
                planText, artifacts, proposed, List.of(), next);
    }

    private List<String> planSteps(String goal) {
        String[] raw = goal.split("[.;]|\\band\\b|\\bthen\\b|,");
        List<String> steps = new ArrayList<>();
        for (String s : raw) {
            String t = s.trim();
            if (!t.isEmpty()) {
                steps.add(t);
            }
        }
        if (steps.isEmpty()) {
            steps.add(goal.trim());
        }
        return steps;
    }

    private List<String> proposeFiles(String goal) {
        String base = classNameFrom(goal);
        return List.of(base + ".java", base + "Test.java");
    }

    private String classNameFrom(String goal) {
        StringBuilder sb = new StringBuilder();
        int words = 0;
        for (String w : goal.replaceAll("[^A-Za-z0-9 ]", " ").trim().split("\\s+")) {
            if (w.isBlank()) {
                continue;
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
            if (++words >= 3) {
                break;
            }
        }
        return sb.length() == 0 ? "Implementation" : sb.toString();
    }

    private String renderPlan(String goal, List<String> steps, List<String> files) {
        StringBuilder sb = new StringBuilder("# Implementation Plan\n\n");
        sb.append("Goal: ").append(goal).append("\n\n## Steps\n");
        int i = 1;
        for (String step : steps) {
            sb.append(i++).append(". ").append(step).append('\n');
        }
        sb.append("\n## Proposed files\n");
        files.forEach(f -> sb.append("- ").append(f).append('\n'));
        return sb.toString();
    }
}
