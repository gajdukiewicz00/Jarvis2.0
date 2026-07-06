package org.jarvis.swarm.sandbox;

import org.jarvis.swarm.support.SwarmTestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Snapshot/restore rollback support and artifact-path re-validation on SandboxManager. */
class SandboxSnapshotTest {

    @TempDir
    Path tmp;

    private SandboxManager manager() {
        return SwarmTestFactory.engine(tmp, "READ_FILES").sandbox();
    }

    @Test
    void restoreWithoutAPriorSnapshotThrows() {
        SandboxManager m = manager();
        Sandbox sb = m.create("task-no-snap");
        assertThat(m.hasSnapshot(sb)).isFalse();
        assertThatThrownBy(() -> m.restore(sb)).isInstanceOf(SandboxException.class);
    }

    @Test
    void snapshotThenRestoreRevertsAWrite() {
        SandboxManager m = manager();
        Sandbox sb = m.create("task-snap");
        m.writeFile(sb, "before.txt", "original");

        m.snapshot(sb);
        assertThat(m.hasSnapshot(sb)).isTrue();

        m.writeFile(sb, "after.txt", "applied-change");
        assertThat(Files.exists(sb.dir().resolve("after.txt"))).isTrue();

        m.restore(sb);

        assertThat(Files.exists(sb.dir().resolve("before.txt"))).isTrue();
        assertThat(Files.exists(sb.dir().resolve("after.txt"))).isFalse();
    }

    @Test
    void snapshotOfAnEmptySandboxThenRestoreLeavesItEmpty() {
        SandboxManager m = manager();
        Sandbox sb = m.create("task-snap-empty");

        m.snapshot(sb);
        m.writeFile(sb, "written-after-snapshot.txt", "x");
        m.restore(sb);

        try (var files = Files.list(sb.dir())) {
            assertThat(files.findAny()).isEmpty();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void validateArtifactPathAcceptsAPathInsideItsOwnSandbox() {
        SandboxManager m = manager();
        Sandbox sb = m.create("task-artifact-ok");
        Path file = m.writeFile(sb, "DIFF.patch", "diff content");

        Path validated = m.validateArtifactPath(sb, file.toString());

        assertThat(validated).isEqualTo(file);
    }

    @Test
    void validateArtifactPathRejectsAPathOutsideItsSandbox() {
        SandboxManager m = manager();
        Sandbox sb = m.create("task-artifact-escape");

        assertThatThrownBy(() -> m.validateArtifactPath(sb, "/etc/passwd"))
                .isInstanceOf(SandboxException.class);
    }

    @Test
    void validateArtifactPathRejectsBlankOrNullByte() {
        SandboxManager m = manager();
        Sandbox sb = m.create("task-artifact-blank");

        assertThatThrownBy(() -> m.validateArtifactPath(sb, "")).isInstanceOf(SandboxException.class);
        assertThatThrownBy(() -> m.validateArtifactPath(sb, null)).isInstanceOf(SandboxException.class);
        assertThatThrownBy(() -> m.validateArtifactPath(sb, "evil\0.txt")).isInstanceOf(SandboxException.class);
    }
}
