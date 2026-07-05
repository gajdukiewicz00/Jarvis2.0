package org.jarvis.swarm.executor.role.coder;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitDiffReportTest {

    @Test
    void summarizesChangedFilesAndAddedLineCount() {
        GitDiffReport report = GitDiffReport.from(List.of(
                new GitDiffReport.ProposedFile("proposed/Foo.java", "line1\nline2"),
                new GitDiffReport.ProposedFile("proposed/FooTest.java", "test1")));

        assertThat(report.changedFiles()).containsExactly("proposed/Foo.java", "proposed/FooTest.java");
        assertThat(report.linesAdded()).isEqualTo(3);
        assertThat(report.unifiedDiff()).contains("+++ b/proposed/Foo.java");
        assertThat(report.unifiedDiff()).contains("+++ b/proposed/FooTest.java");
    }

    @Test
    void emptyProposalYieldsEmptyReport() {
        GitDiffReport report = GitDiffReport.from(List.of());

        assertThat(report.changedFiles()).isEmpty();
        assertThat(report.linesAdded()).isZero();
        assertThat(report.unifiedDiff()).isEmpty();
    }

    @Test
    void nullListsNormalizeToEmpty() {
        GitDiffReport report = new GitDiffReport(null, 0, null);

        assertThat(report.changedFiles()).isEmpty();
        assertThat(report.unifiedDiff()).isEmpty();
    }
}
