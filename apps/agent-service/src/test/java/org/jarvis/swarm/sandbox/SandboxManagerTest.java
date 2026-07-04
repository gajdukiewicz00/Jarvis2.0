package org.jarvis.swarm.sandbox;

import org.jarvis.swarm.support.SwarmTestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxManagerTest {

    @TempDir
    Path tmp;

    private SandboxManager manager() {
        return SwarmTestFactory.engine(tmp, "READ_FILES").sandbox();
    }

    @Test
    void writesStayInsideSandbox() {
        SandboxManager m = manager();
        Sandbox sb = m.create("task-1");
        Path file = m.writeFile(sb, "out/plan.md", "hello");
        assertThat(file.startsWith(sb.dir())).isTrue();
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    void rejectsTraversalInArtifactName() {
        SandboxManager m = manager();
        Sandbox sb = m.create("task-2");
        assertThatThrownBy(() -> m.resolve(sb, "../escape.txt")).isInstanceOf(SandboxException.class);
        assertThatThrownBy(() -> m.resolve(sb, "/etc/passwd")).isInstanceOf(SandboxException.class);
    }

    @Test
    void rejectsTaskIdThatEscapesRoot() {
        SandboxManager m = manager();
        assertThatThrownBy(() -> m.create("../evil")).isInstanceOf(SandboxException.class);
        assertThatThrownBy(() -> m.create("a/b")).isInstanceOf(SandboxException.class);
    }

    @Test
    void cleanupRemovesOnlyTheSandboxAndRefusesRoot() {
        SandboxManager m = manager();
        Sandbox sb = m.create("task-3");
        m.writeFile(sb, "f.txt", "x");
        m.cleanup(sb);
        assertThat(Files.exists(sb.dir())).isFalse();
        assertThat(Files.exists(m.root())).isTrue(); // root preserved

        Sandbox fakeRoot = new Sandbox("root", m.root());
        assertThatThrownBy(() -> m.cleanup(fakeRoot)).isInstanceOf(SandboxException.class);
    }

    @Test
    void cleanupOfAlreadyMissingSandboxIsANoOp() {
        SandboxManager m = manager();
        Sandbox sb = m.create("task-missing");
        m.cleanup(sb); // removes the (empty) dir
        assertThat(Files.exists(sb.dir())).isFalse();

        m.cleanup(sb); // second call: dir no longer exists -> early-return branch
        assertThat(Files.exists(sb.dir())).isFalse();
    }

    @Test
    void rejectsBlankOrNullTaskId() {
        SandboxManager m = manager();
        assertThatThrownBy(() -> m.create("")).isInstanceOf(SandboxException.class);
        assertThatThrownBy(() -> m.create(null)).isInstanceOf(SandboxException.class);
    }

    @Test
    void rejectsBlankArtifactName() {
        SandboxManager m = manager();
        Sandbox sb = m.create("task-blank-name");
        assertThatThrownBy(() -> m.resolve(sb, "")).isInstanceOf(SandboxException.class);
        assertThatThrownBy(() -> m.resolve(sb, null)).isInstanceOf(SandboxException.class);
    }

    @Test
    void rejectsArtifactNameWithNullByte() {
        SandboxManager m = manager();
        Sandbox sb = m.create("task-null-byte");
        assertThatThrownBy(() -> m.resolve(sb, "evil\0name.txt")).isInstanceOf(SandboxException.class);
    }

    @Test
    void sizeOrZeroReportsRealSizeAndZeroForMissingFile() throws Exception {
        SandboxManager m = manager();
        Sandbox sb = m.create("task-size");
        Path file = m.writeFile(sb, "data.txt", "hello world");
        assertThat(m.sizeOrZero(file)).isEqualTo(Files.size(file));
        assertThat(m.sizeOrZero(sb.dir().resolve("missing.txt"))).isEqualTo(0L);
    }
}
