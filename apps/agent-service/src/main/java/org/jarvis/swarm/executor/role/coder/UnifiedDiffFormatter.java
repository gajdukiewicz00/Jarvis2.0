package org.jarvis.swarm.executor.role.coder;

import java.util.List;

/**
 * Formats proposed new files as standard unified-diff / patch text — the format
 * {@code git apply} or {@code patch} understands. CODER only ever proposes brand-new
 * files (it never edits existing repository content), so every hunk is an
 * additions-only "new file" diff against {@code /dev/null}, safe to review in full
 * before anyone opts in to actually applying it.
 */
public final class UnifiedDiffFormatter {

    private UnifiedDiffFormatter() {
    }

    /** Format a single proposed new file as a unified-diff hunk. */
    public static String formatNewFile(String relativePath, String content) {
        String body = content == null ? "" : content;
        String[] rawLines = body.split("\n", -1);
        // A trailing split artifact from a final newline shouldn't count as an extra line.
        int lineCount = rawLines.length > 0 && rawLines[rawLines.length - 1].isEmpty()
                ? rawLines.length - 1
                : rawLines.length;

        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/").append(relativePath).append(" b/").append(relativePath).append('\n');
        sb.append("new file mode 100644\n");
        sb.append("--- /dev/null\n");
        sb.append("+++ b/").append(relativePath).append('\n');
        sb.append("@@ -0,0 +1,").append(lineCount).append(" @@\n");
        for (int i = 0; i < lineCount; i++) {
            sb.append('+').append(rawLines[i]).append('\n');
        }
        return sb.toString();
    }

    /** Join several per-file diffs into one combined patch document. */
    public static String combine(List<String> perFileDiffs) {
        return String.join("\n", perFileDiffs);
    }
}
