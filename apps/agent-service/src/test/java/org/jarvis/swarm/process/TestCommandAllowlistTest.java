package org.jarvis.swarm.process;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestCommandAllowlistTest {

    @Test
    void allowsTheTinySafeCommandsWithAnyArguments() {
        assertThat(TestCommandAllowlist.isAllowed(List.of("echo", "hi"))).isTrue();
        assertThat(TestCommandAllowlist.isAllowed(List.of("false"))).isTrue();
        assertThat(TestCommandAllowlist.isAllowed(List.of("ls", "-la", "/anything"))).isTrue();
    }

    @Test
    void allowsPlainMavenTest() {
        assertThat(TestCommandAllowlist.isAllowed(List.of("mvn", "test"))).isTrue();
        assertThat(TestCommandAllowlist.isAllowed(List.of("mvn", "-q", "test"))).isTrue();
        assertThat(TestCommandAllowlist.isAllowed(List.of("mvn", "-q", "-pl", "apps/agent-service", "test")))
                .isTrue();
    }

    @Test
    void rejectsMavenWithDisallowedFlagsOrTraversal() {
        assertThat(TestCommandAllowlist.isAllowed(List.of("mvn", "-Dexec.executable=/bin/sh", "test"))).isFalse();
        assertThat(TestCommandAllowlist.isAllowed(List.of("mvn", "-q", "-pl", "../../etc", "test"))).isFalse();
        assertThat(TestCommandAllowlist.isAllowed(List.of("mvn", "-q", "-pl", "/etc/passwd", "test"))).isFalse();
        assertThat(TestCommandAllowlist.isAllowed(List.of("mvn", "test", "extra-goal"))).isFalse();
        assertThat(TestCommandAllowlist.isAllowed(List.of("mvn", "clean", "install"))).isFalse();
    }

    @Test
    void allowsGradleAndGradlewTestOnly() {
        assertThat(TestCommandAllowlist.isAllowed(List.of("gradle", "test"))).isTrue();
        assertThat(TestCommandAllowlist.isAllowed(List.of("./gradlew", "test"))).isTrue();
        assertThat(TestCommandAllowlist.isAllowed(List.of("gradle", "test", "--info"))).isFalse();
    }

    @Test
    void allowsNpmTestVariants() {
        assertThat(TestCommandAllowlist.isAllowed(List.of("npm", "test"))).isTrue();
        assertThat(TestCommandAllowlist.isAllowed(List.of("npm", "run", "test"))).isTrue();
        assertThat(TestCommandAllowlist.isAllowed(List.of("npm", "run", "build"))).isFalse();
    }

    @Test
    void allowsPytestWithOptionalSinglePathArgument() {
        assertThat(TestCommandAllowlist.isAllowed(List.of("pytest"))).isTrue();
        assertThat(TestCommandAllowlist.isAllowed(List.of("pytest", "tests/unit"))).isTrue();
        assertThat(TestCommandAllowlist.isAllowed(List.of("pytest", "-x"))).isFalse();
        assertThat(TestCommandAllowlist.isAllowed(List.of("pytest", "../secrets"))).isFalse();
        assertThat(TestCommandAllowlist.isAllowed(List.of("pytest", "a", "b"))).isFalse();
    }

    @Test
    void rejectsUnknownBinariesAndEmptyCommands() {
        assertThat(TestCommandAllowlist.isAllowed(List.of("rm", "-rf", "/"))).isFalse();
        assertThat(TestCommandAllowlist.isAllowed(List.of())).isFalse();
        assertThat(TestCommandAllowlist.isAllowed(null)).isFalse();
    }
}
