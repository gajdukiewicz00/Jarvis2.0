package org.jarvis.media.workspace;

import org.jarvis.media.support.MediaTestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceManagerTest {

    @TempDir
    Path tmp;

    private WorkspaceManager manager() {
        return MediaTestFactory.workspace(tmp);
    }

    @Test
    void resolveInWorkspaceKeepsArtifactsInsideWorkspace() {
        WorkspaceManager m = manager();
        Path resolved = m.resolveInWorkspace("job1/audio.wav");
        assertThat(resolved.startsWith(tmp.toAbsolutePath().normalize())).isTrue();
        assertThat(resolved.getFileName().toString()).isEqualTo("audio.wav");
    }

    @Test
    void resolveInWorkspaceRejectsTraversal() {
        WorkspaceManager m = manager();
        assertThatThrownBy(() -> m.resolveInWorkspace("../escape.wav"))
                .isInstanceOf(PathValidationException.class);
    }

    @Test
    void resolveInWorkspaceRejectsAbsoluteName() {
        WorkspaceManager m = manager();
        assertThatThrownBy(() -> m.resolveInWorkspace("/etc/passwd"))
                .isInstanceOf(PathValidationException.class);
    }

    @Test
    void validateInputPathAcceptsPathInsideWorkspace() {
        WorkspaceManager m = manager();
        Path input = tmp.resolve("movie.mkv");
        Path validated = m.validateInputPath(input.toString());
        assertThat(validated).isEqualTo(input.toAbsolutePath().normalize());
    }

    @Test
    void validateInputPathRejectsTraversalOutsideRoots() {
        WorkspaceManager m = manager();
        assertThatThrownBy(() -> m.validateInputPath(tmp + "/../../etc/passwd"))
                .isInstanceOf(PathValidationException.class)
                .hasMessageContaining("traversal");
    }

    @Test
    void validateInputPathRejectsUnrelatedAbsolutePath() {
        WorkspaceManager m = manager();
        assertThatThrownBy(() -> m.validateInputPath("/etc/shadow"))
                .isInstanceOf(PathValidationException.class)
                .hasMessageContaining("outside allowed roots");
    }

    @Test
    void validateInputPathRejectsNullByte() {
        WorkspaceManager m = manager();
        String withNullByte = "movie" + ((char) 0) + ".mkv";
        assertThatThrownBy(() -> m.validateInputPath(withNullByte))
                .isInstanceOf(PathValidationException.class)
                .hasMessageContaining("null byte");
    }

    @Test
    void validateInputPathRejectsBlank() {
        WorkspaceManager m = manager();
        assertThatThrownBy(() -> m.validateInputPath("   "))
                .isInstanceOf(PathValidationException.class)
                .hasMessageContaining("blank");
    }
}
