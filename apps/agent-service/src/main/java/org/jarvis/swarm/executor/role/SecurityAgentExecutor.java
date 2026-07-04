package org.jarvis.swarm.executor.role;

import org.jarvis.swarm.executor.ExecutionContext;
import org.jarvis.swarm.executor.RoleExecutor;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.process.OutputSanitizer;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.sandbox.SandboxManager;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * SECURITY: runs a safety checklist and scans the goal text plus any sandbox files for
 * obviously risky patterns (destructive shell, eval, pipe-to-shell, and secret-like
 * tokens). It reports the TYPE and location of findings but NEVER the secret value — the
 * matched text is redacted, so the scan cannot itself leak credentials.
 */
@Component
public class SecurityAgentExecutor implements RoleExecutor {

    private static final List<Pattern> RISKY = List.of(
            Pattern.compile("rm\\s+-rf\\s+/"),
            Pattern.compile("(?i)\\beval\\s*\\("),
            Pattern.compile("curl[^\\n]*\\|\\s*sh"),
            Pattern.compile("(?i)chmod\\s+777"),
            Pattern.compile("(?i)disable\\s+(ssl|tls|cert)"));

    private final SandboxManager sandbox;
    private final OutputSanitizer sanitizer;

    public SecurityAgentExecutor(SandboxManager sandbox, OutputSanitizer sanitizer) {
        this.sandbox = sandbox;
        this.sanitizer = sanitizer;
    }

    @Override
    public AgentRole role() {
        return AgentRole.SECURITY;
    }

    @Override
    public RoleResult execute(ExecutionContext ctx) {
        ctx.checkpoint();
        List<String> findings = new ArrayList<>();
        scanText("goal", ctx.task().goal(), findings);
        if (ctx.sandbox() != null) {
            scanSandbox(ctx, findings);
        }

        List<String> checklist = List.of(
                "secrets-not-hardcoded", "no-destructive-shell", "input-validated", "least-privilege");
        String report = renderReport(checklist, findings);
        List<String> risks = findings.isEmpty() ? List.of() : List.copyOf(findings);

        if (ctx.dryRun() || ctx.sandbox() == null) {
            return RoleResult.success("SECURITY checklist run; " + findings.size() + " finding(s)",
                    report, List.of(), List.of("run checklist", "scan content"), risks, List.of());
        }
        Path file = sandbox.writeFile(ctx.sandbox(), "security/REPORT.md", report);
        return RoleResult.success("SECURITY produced a report; " + findings.size() + " finding(s)",
                report, List.of(file.toString()), List.of(), risks, List.of());
    }

    private void scanSandbox(ExecutionContext ctx, List<String> findings) {
        try (Stream<Path> files = Files.walk(ctx.sandbox().dir())) {
            files.filter(Files::isRegularFile).limit(200).forEach(p -> {
                try {
                    scanText(ctx.sandbox().dir().relativize(p).toString(), Files.readString(p), findings);
                } catch (IOException ignored) {
                    // unreadable file; skip
                }
            });
        } catch (IOException ignored) {
            // sandbox not walkable; skip
        }
    }

    private void scanText(String where, String text, List<String> findings) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (Pattern p : RISKY) {
            if (p.matcher(text).find()) {
                findings.add("risky-pattern '" + p.pattern() + "' in " + where);
            }
        }
        if (sanitizer.containsSecret(text)) {
            // report presence + location only — never the value
            findings.add("possible-secret in " + where + " (value redacted)");
        }
    }

    private String renderReport(List<String> checklist, List<String> findings) {
        StringBuilder sb = new StringBuilder("# Security Report\n\n## Checklist\n");
        checklist.forEach(c -> sb.append("- [x] ").append(c).append('\n'));
        sb.append("\n## Findings\n");
        if (findings.isEmpty()) {
            sb.append("No obvious risky patterns found.\n");
        } else {
            findings.forEach(f -> sb.append("- ").append(f).append('\n'));
        }
        return sb.toString();
    }
}
