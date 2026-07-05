package org.jarvis.visionsecurity.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Covers {@link ScreenContextService}, mocking its shell/OCR/tagging collaborators. */
class ScreenContextServiceTest {

    private final ShellCommandRunner commandRunner = mock(ShellCommandRunner.class);
    private final SemanticTagger semanticTagger = mock(SemanticTagger.class);
    private final OcrService ocrService = mock(OcrService.class);
    private final ScreenContextService service = new ScreenContextService(commandRunner, semanticTagger, ocrService);

    @Test
    void collectAssemblesEvidenceFromWindowTitleProcessAndOcr(@TempDir Path tempDir) throws Exception {
        Path screenshot = tempDir.resolve("shot.png");
        Path ocrOutput = tempDir.resolve("shot.txt");
        when(commandRunner.isAvailable("xdotool")).thenReturn(true);
        when(commandRunner.execute(List.of("xdotool", "getactivewindow", "getwindowname")))
                .thenReturn(new ShellCommandRunner.CommandResult(0, "Terminal"));
        when(commandRunner.execute(List.of("xdotool", "getactivewindow", "getwindowpid")))
                .thenReturn(new ShellCommandRunner.CommandResult(0, "1234"));
        when(commandRunner.execute(List.of("ps", "-p", "1234", "-o", "comm=")))
                .thenReturn(new ShellCommandRunner.CommandResult(0, "bash"));
        when(ocrService.extractText(eq(screenshot), eq(ocrOutput)))
                .thenReturn(new OcrService.OcrResult("sudo cat /etc/hosts", true, "OCR extracted", ocrOutput.toString()));
        when(semanticTagger.deriveTags("Terminal", "bash", "sudo cat /etc/hosts"))
                .thenReturn(List.of("DEVELOPMENT"));

        ScreenContextService.ScreenContextCapture capture = service.collect(screenshot, ocrOutput);

        assertThat(capture.evidence().activeWindowTitle()).isEqualTo("Terminal");
        assertThat(capture.evidence().activeProcessName()).isEqualTo("bash");
        assertThat(capture.evidence().ocrText()).isEqualTo("sudo cat /etc/hosts");
        assertThat(capture.evidence().semanticTags()).containsExactly("DEVELOPMENT");
        assertThat(capture.ocrTextPath()).isEqualTo(ocrOutput.toString());
    }

    @Test
    void detectDisplayServerReflectsWaylandEnvironmentVariable() {
        assertThat(service.detectDisplayServer()).isNotNull();
    }

    @Test
    void activeWindowTitleIsBlankWhenXdotoolUnavailable() {
        when(commandRunner.isAvailable("xdotool")).thenReturn(false);

        assertThat(service.activeWindowTitle()).isEmpty();
    }

    @Test
    void activeWindowTitleIsBlankWhenCommandExitsNonZero() throws Exception {
        when(commandRunner.isAvailable("xdotool")).thenReturn(true);
        when(commandRunner.execute(List.of("xdotool", "getactivewindow", "getwindowname")))
                .thenReturn(new ShellCommandRunner.CommandResult(1, "no window"));

        assertThat(service.activeWindowTitle()).isEmpty();
    }

    @Test
    void activeWindowTitleIsBlankWhenCommandThrows() throws Exception {
        when(commandRunner.isAvailable("xdotool")).thenReturn(true);
        when(commandRunner.execute(List.of("xdotool", "getactivewindow", "getwindowname")))
                .thenThrow(new java.io.IOException("boom"));

        assertThat(service.activeWindowTitle()).isEmpty();
    }

    @Test
    void activeProcessNameIsBlankWhenXdotoolUnavailable() {
        when(commandRunner.isAvailable("xdotool")).thenReturn(false);

        assertThat(service.activeProcessName()).isEmpty();
    }

    @Test
    void activeProcessNameIsBlankWhenPidLookupFails() throws Exception {
        when(commandRunner.isAvailable("xdotool")).thenReturn(true);
        when(commandRunner.execute(List.of("xdotool", "getactivewindow", "getwindowpid")))
                .thenReturn(new ShellCommandRunner.CommandResult(1, ""));

        assertThat(service.activeProcessName()).isEmpty();
    }

    @Test
    void activeProcessNameIsBlankWhenPidOutputIsBlank() throws Exception {
        when(commandRunner.isAvailable("xdotool")).thenReturn(true);
        when(commandRunner.execute(List.of("xdotool", "getactivewindow", "getwindowpid")))
                .thenReturn(new ShellCommandRunner.CommandResult(0, "  "));

        assertThat(service.activeProcessName()).isEmpty();
    }

    @Test
    void activeProcessNameIsBlankWhenPsCommandFails() throws Exception {
        when(commandRunner.isAvailable("xdotool")).thenReturn(true);
        when(commandRunner.execute(List.of("xdotool", "getactivewindow", "getwindowpid")))
                .thenReturn(new ShellCommandRunner.CommandResult(0, "1234"));
        when(commandRunner.execute(List.of("ps", "-p", "1234", "-o", "comm=")))
                .thenReturn(new ShellCommandRunner.CommandResult(1, ""));

        assertThat(service.activeProcessName()).isEmpty();
    }

    @Test
    void activeProcessNameIsBlankWhenCommandThrows() throws Exception {
        when(commandRunner.isAvailable("xdotool")).thenReturn(true);
        when(commandRunner.execute(List.of("xdotool", "getactivewindow", "getwindowpid")))
                .thenThrow(new java.io.IOException("boom"));

        assertThat(service.activeProcessName()).isEmpty();
    }
}
