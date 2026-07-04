package org.jarvis.visionsecurity.service;

import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScreenshotServiceTest {

    private final ShellCommandRunner runner = mock(ShellCommandRunner.class);
    private final ScreenshotService service = new ScreenshotService(runner);

    @Test
    void capabilityPrefersGnomeScreenshotWhenAvailable() {
        when(runner.isAvailable("gnome-screenshot")).thenReturn(true);

        CapabilityStatus status = service.capabilityStatus();

        assertThat(status.state()).isEqualTo("AVAILABLE");
        assertThat(status.detail()).contains("gnome-screenshot");
    }

    @Test
    void capabilityFallsBackToScrotWhenGnomeUnavailable() {
        when(runner.isAvailable("gnome-screenshot")).thenReturn(false);
        when(runner.isAvailable("scrot")).thenReturn(true);

        CapabilityStatus status = service.capabilityStatus();

        assertThat(status.state()).isEqualTo("AVAILABLE");
        assertThat(status.detail()).contains("scrot");
    }

    @Test
    void capabilityFallsBackToImageMagickWhenOthersUnavailable() {
        when(runner.isAvailable("gnome-screenshot")).thenReturn(false);
        when(runner.isAvailable("scrot")).thenReturn(false);
        when(runner.isAvailable("import")).thenReturn(true);

        CapabilityStatus status = service.capabilityStatus();

        assertThat(status.state()).isEqualTo("AVAILABLE");
        assertThat(status.detail()).contains("ImageMagick");
    }

    @Test
    void captureUsesGnomeScreenshotAndReturnsTargetWhenSuccessful(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("shots/frame.png");
        when(runner.isAvailable("gnome-screenshot")).thenReturn(true);
        when(runner.execute(eq(List.of("gnome-screenshot", "-f", target.toString()))))
                .thenAnswer(inv -> {
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, "png");
                    return new ShellCommandRunner.CommandResult(0, "");
                });

        Path actual = service.capture(target);

        assertThat(actual).isEqualTo(target);
        assertThat(Files.exists(target)).isTrue();
        verify(runner).execute(eq(List.of("gnome-screenshot", "-f", target.toString())));
    }

    @Test
    void captureRaisesIoErrorWhenBackendExitsNonZero(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("frame.png");
        when(runner.isAvailable("gnome-screenshot")).thenReturn(true);
        when(runner.execute(eq(List.of("gnome-screenshot", "-f", target.toString()))))
                .thenReturn(new ShellCommandRunner.CommandResult(1, "display not found"));

        assertThatThrownBy(() -> service.capture(target))
                .hasMessageContaining("Screenshot command failed")
                .hasMessageContaining("display not found");
    }

    @Test
    void captureRaisesWhenBackendReportsSuccessButFileMissing(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("frame.png");
        when(runner.isAvailable("gnome-screenshot")).thenReturn(true);
        when(runner.execute(eq(List.of("gnome-screenshot", "-f", target.toString()))))
                .thenReturn(new ShellCommandRunner.CommandResult(0, ""));

        assertThatThrownBy(() -> service.capture(target))
                .hasMessageContaining("file was not created");
    }
}
