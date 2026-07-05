package org.jarvis.swarm.executor.role.coder;

import java.util.ArrayList;
import java.util.List;

/**
 * Summary of a CODER patch proposal: which files it would create, how many lines, and
 * the full unified-diff text. This is a PROPOSAL only — CODER writes exclusively inside
 * its own sandbox (see {@code CoderAgentExecutor}), never the real repository, so
 * "applying" it always means writing to that sandbox, gated by the task's dryRun flag.
 *
 * @param changedFiles relative paths of every proposed new file
 * @param linesAdded   total added lines across all proposed files
 * @param unifiedDiff  the combined patch text ({@code git apply}-compatible)
 */
public record GitDiffReport(List<String> changedFiles, int linesAdded, String unifiedDiff) {

    public GitDiffReport {
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
        unifiedDiff = unifiedDiff == null ? "" : unifiedDiff;
    }

    /** A proposed new file: its sandbox-relative path and full content. */
    public record ProposedFile(String path, String content) {
    }

    public static GitDiffReport from(List<ProposedFile> files) {
        List<String> paths = new ArrayList<>();
        List<String> diffs = new ArrayList<>();
        int linesAdded = 0;
        for (ProposedFile file : files) {
            String diff = UnifiedDiffFormatter.formatNewFile(file.path(), file.content());
            paths.add(file.path());
            diffs.add(diff);
            linesAdded += countAddedLines(diff);
        }
        return new GitDiffReport(paths, linesAdded, UnifiedDiffFormatter.combine(diffs));
    }

    private static int countAddedLines(String diff) {
        return (int) diff.lines().filter(line -> line.startsWith("+") && !line.startsWith("+++")).count();
    }
}
