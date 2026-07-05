package org.jarvis.swarm.executor.role.coder;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnifiedDiffFormatterTest {

    @Test
    void formatsANewFileAsAnAdditionsOnlyHunk() {
        String diff = UnifiedDiffFormatter.formatNewFile("proposed/Foo.java", "line one\nline two");

        assertThat(diff).startsWith("diff --git a/proposed/Foo.java b/proposed/Foo.java\n");
        assertThat(diff).contains("new file mode 100644\n");
        assertThat(diff).contains("--- /dev/null\n");
        assertThat(diff).contains("+++ b/proposed/Foo.java\n");
        assertThat(diff).contains("@@ -0,0 +1,2 @@\n");
        assertThat(diff).contains("+line one\n");
        assertThat(diff).contains("+line two\n");
    }

    @Test
    void trailingNewlineDoesNotCountAsAnExtraLine() {
        String diff = UnifiedDiffFormatter.formatNewFile("A.txt", "one\ntwo\n");

        assertThat(diff).contains("@@ -0,0 +1,2 @@\n");
    }

    @Test
    void emptyContentProducesAZeroLineHunk() {
        String diff = UnifiedDiffFormatter.formatNewFile("Empty.txt", "");

        assertThat(diff).contains("@@ -0,0 +1,0 @@\n");
    }

    @Test
    void combineJoinsMultipleDiffsWithBlankLineSeparator() {
        String a = UnifiedDiffFormatter.formatNewFile("A.txt", "a");
        String b = UnifiedDiffFormatter.formatNewFile("B.txt", "b");

        String combined = UnifiedDiffFormatter.combine(List.of(a, b));

        assertThat(combined).contains(a);
        assertThat(combined).contains(b);
        assertThat(combined.indexOf(a)).isLessThan(combined.indexOf(b));
    }
}
