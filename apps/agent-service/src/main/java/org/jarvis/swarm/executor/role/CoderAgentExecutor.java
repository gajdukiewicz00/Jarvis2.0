package org.jarvis.swarm.executor.role;

import org.jarvis.swarm.executor.ExecutionContext;
import org.jarvis.swarm.executor.RoleExecutor;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.executor.role.coder.GitDiffReport;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.sandbox.Sandbox;
import org.jarvis.swarm.sandbox.SandboxManager;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CODER: turns a goal into a concrete implementation plan and a unified-diff PATCH
 * PROPOSAL for the files it would create. dryRun (the default) returns the plan and the
 * patch text without touching disk. Applying — i.e. actually writing the plan, stub
 * files, and the patch document — only happens when the task explicitly sets
 * {@code dryRun=false}, and even then ONLY INTO ITS OWN SANDBOX (never the real
 * repository, which would require WRITE_FILES and is intentionally never done here).
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
        String planText = renderPlan(goal, steps, files);

        List<GitDiffReport.ProposedFile> proposedFiles = new ArrayList<>();
        for (String file : files) {
            proposedFiles.add(new GitDiffReport.ProposedFile("proposed/" + file, stubContent(goal)));
        }
        GitDiffReport diffReport = GitDiffReport.from(proposedFiles);

        List<String> proposed = new ArrayList<>();
        proposed.add("Implementation plan with " + steps.size() + " step(s)");
        files.forEach(f -> proposed.add("create " + f));
        proposed.add("patch proposal: " + diffReport.changedFiles().size() + " new file(s), +"
                + diffReport.linesAdded() + " line(s)");

        List<String> next = List.of(
                "Review the patch proposal before applying",
                "Applying to the real repository requires WRITE_FILES permission (not granted here)");

        if (ctx.dryRun() || ctx.sandbox() == null) {
            String output = planText + "\n\n## Patch proposal\n\n" + diffReport.unifiedDiff();
            return RoleResult.success(
                    "CODER plan (dry-run): " + steps.size() + " steps, " + files.size() + " proposed files",
                    output, List.of(), proposed, List.of(), next);
        }

        Sandbox sb = ctx.sandbox();
        List<String> artifacts = new ArrayList<>();
        Path plan = sandbox.writeFile(sb, "PLAN.md", planText);
        artifacts.add(plan.toString());
        for (GitDiffReport.ProposedFile file : proposedFiles) {
            ctx.checkpoint();
            Path stub = sandbox.writeFile(sb, file.path(), file.content());
            artifacts.add(stub.toString());
        }
        Path patch = sandbox.writeFile(sb, "DIFF.patch", diffReport.unifiedDiff());
        artifacts.add(patch.toString());

        return RoleResult.success(
                "CODER applied a plan and " + files.size() + " stub file(s) in sandbox (+"
                        + diffReport.linesAdded() + " lines, see DIFF.patch)",
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

    private String stubContent(String goal) {
        return "// Proposed by CODER agent for goal:\n// " + goal + "\n// TODO: implement\n";
    }
}
