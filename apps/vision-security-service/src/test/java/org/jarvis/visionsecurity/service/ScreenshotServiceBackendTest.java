package org.jarvis.visionsecurity.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Additional {@link ScreenshotService} branches not covered by
 * {@link ScreenshotServiceTest}: the scrot/ImageMagick capture backends, and
 * the Java-Robot capability fallback line.
 *
 * <p>The actual Robot-based screen grab (used only when no CLI backend is
 * installed AND the JVM has a real display) is intentionally NOT exercised
 * here: it would capture the operator's live desktop during a test run, and
 * its "headless session" failure branch is environment-dependent (this
 * sandbox's Surefire fork runs with a real X11 display, so
 * {@code GraphicsEnvironment.isHeadless()} is {@code false} here). Neither
 * branch can be driven deterministically without faking a JDK static utility
 * or touching the real screen, so both are left honestly uncovered.
 */
class ScreenshotServiceBackendTest {

    private final ShellCommandRunner runner = mock(ShellCommandRunner.class);
    private final ScreenshotService service = new ScreenshotService(runner);

    @Test
    void captureUsesScrotWhenGnomeScreenshotUnavailable(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("frame.png");
        when(runner.isAvailable("gnome-screenshot")).thenReturn(false);
        when(runner.isAvailable("scrot")).thenReturn(true);
        when(runner.execute(eq(List.of("scrot", target.toString())))).thenAnswer(inv -> {
            Files.createDirectories(target.getParent());
            Files.writeString(target, "png");
            return new ShellCommandRunner.CommandResult(0, "");
        });

        Path actual = service.capture(target);

        assertThat(actual).isEqualTo(target);
        verify(runner).execute(eq(List.of("scrot", target.toString())));
    }

    @Test
    void captureUsesImageMagickWhenOthersUnavailable(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("frame.png");
        when(runner.isAvailable("gnome-screenshot")).thenReturn(false);
        when(runner.isAvailable("scrot")).thenReturn(false);
        when(runner.isAvailable("import")).thenReturn(true);
        when(runner.execute(eq(List.of("import", "-window", "root", target.toString())))).thenAnswer(inv -> {
            Files.createDirectories(target.getParent());
            Files.writeString(target, "png");
            return new ShellCommandRunner.CommandResult(0, "");
        });

        Path actual = service.capture(target);

        assertThat(actual).isEqualTo(target);
        verify(runner).execute(eq(List.of("import", "-window", "root", target.toString())));
    }

    @Test
    void capabilityReportsJavaRobotFallbackWhenNoCliBackendAndDisplayIsAvailable() {
        // Only meaningful on a Surefire fork that has a real (or virtual) display;
        // self-skips on a genuinely headless runner instead of asserting a false result.
        assumeFalse(GraphicsEnvironment.isHeadless());
        when(runner.isAvailable("gnome-screenshot")).thenReturn(false);
        when(runner.isAvailable("scrot")).thenReturn(false);
        when(runner.isAvailable("import")).thenReturn(false);

        var status = service.capabilityStatus();

        assertThat(status.state()).isEqualTo("AVAILABLE");
        assertThat(status.detail()).contains("Java Robot fallback");
    }
}
