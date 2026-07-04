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
}
