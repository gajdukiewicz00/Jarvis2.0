package org.jarvis.visionsecurity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Regression tests for the path-traversal gap in {@link EnrollmentStore#userDirectory(String)}:
 * a userId of "." or ".." previously survived {@code sanitize()} unchanged (both characters are
 * in the allow-list), causing {@code userDirectory("..")} to resolve one level above the
 * per-user "users" directory (i.e. to the shared storage root itself).
 */
class EnrollmentStorePathTraversalTest {

    @TempDir
    Path tempDir;

    private EnrollmentStore newStore() {
        VisionSecurityProperties properties = new VisionSecurityProperties();
        properties.getStorage().setRoot(tempDir.toString());
        return new EnrollmentStore(properties, new ObjectMapper().findAndRegisterModules());
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", ".", ""})
    void userDirectoryRejectsReservedOrBlankSegments(String maliciousUserId) {
        EnrollmentStore store = newStore();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> store.userDirectory(maliciousUserId));
    }

    @Test
    void userDirectoryRejectsUserIdThatSanitizesToParentTraversal() {
        EnrollmentStore store = newStore();

        // Every character here is outside [a-zA-Z0-9._-], so the old sanitize() collapsed
        // this down to a run of underscores; this case must still resolve safely under the
        // users root (i.e. must not throw) since it does not sanitize down to "." or "..".
        Path resolved = store.userDirectory("!!!");
        Path usersRoot = tempDir.resolve("users");
        assertThat(resolved).isNotEqualTo(usersRoot);
        assertThat(resolved.startsWith(usersRoot)).isTrue();
    }

    @Test
    void userDirectoryStaysContainedForOrdinaryUserId() {
        EnrollmentStore store = newStore();

        Path resolved = store.userDirectory("alice");
        Path usersRoot = tempDir.resolve("users");

        assertThat(resolved).isEqualTo(usersRoot.resolve("alice"));
        assertThat(resolved.startsWith(usersRoot)).isTrue();
    }

    @Test
    void resetRejectsParentTraversalUserIdInsteadOfWipingSharedStorageRoot() {
        EnrollmentStore store = newStore();

        assertThatIllegalArgumentException().isThrownBy(() -> store.reset(".."));
    }
}
