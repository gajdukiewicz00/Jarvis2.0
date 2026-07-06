package org.jarvis.visionsecurity.controller;

import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.jarvis.visionsecurity.service.LocalCvService;
import org.jarvis.visionsecurity.service.cv.AskScreenCvService;
import org.jarvis.visionsecurity.service.cv.ScreenContextCvService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for CRITICAL findings #6/#7: a client-supplied
 * {@code imagePath} on {@code POST /cv/analyze} must never escape the
 * vision-security storage root, whether it feeds the screenshot writer
 * ({@code source=screenshot} -> {@link LocalCvService#analyzeScreenshot})
 * or the OCR reader ({@code source=file} -> {@link LocalCvService#analyzeFile}).
 *
 * <p>Before the fix, {@code Path.of(request.imagePath())} was passed straight
 * through with no containment check, allowing an arbitrary-path PNG
 * write/overwrite (capture sink) or an arbitrary-file read (OCR sink), e.g.
 * {@code imagePath=/etc/passwd} or {@code imagePath=../../etc/passwd}.
 */
class CvAnalysisControllerPathTraversalTest {

    @TempDir
    Path baseDir;

    private LocalCvService cvService;
    private CvAnalysisController controller;

    @BeforeEach
    void setUp() {
        cvService = mock(LocalCvService.class);
        VisionSecurityProperties properties = new VisionSecurityProperties();
        properties.getStorage().setRoot(baseDir.toString());
        controller = new CvAnalysisController(
                cvService, mock(ScreenContextCvService.class), mock(AskScreenCvService.class), properties);
    }

    private static CvAnalysisResult stubResult() {
        return new CvAnalysisResult("file", "x", 1, 1, "", List.of(),
                "tesseract", "eng", 1L, Instant.now(), true, null);
    }

    @Test
    void relativeTraversalOnFileSourceIsRejected() {
        assertThatThrownBy(() -> controller.analyze(
                new CvAnalysisController.AnalyzeRequest("file", "../../etc/passwd")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void absoluteEscapeOnFileSourceIsRejected() {
        assertThatThrownBy(() -> controller.analyze(
                new CvAnalysisController.AnalyzeRequest("file", "/etc/passwd")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void relativeTraversalOnScreenshotSourceIsRejected() {
        assertThatThrownBy(() -> controller.analyze(
                new CvAnalysisController.AnalyzeRequest("screenshot", "../../etc/cron.d/evil")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void absoluteEscapeOnScreenshotSourceIsRejected() {
        assertThatThrownBy(() -> controller.analyze(
                new CvAnalysisController.AnalyzeRequest("screenshot", "/tmp/evil.png")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legitInBasePathIsAcceptedForFileSource() throws IOException {
        Path legit = baseDir.resolve("sample.png");
        Files.writeString(legit, "existence is enough for this unit test");
        when(cvService.analyzeFile(any())).thenReturn(stubResult());

        controller.analyze(new CvAnalysisController.AnalyzeRequest("file", "sample.png"));

        ArgumentCaptor<Path> captor = ArgumentCaptor.forClass(Path.class);
        verify(cvService).analyzeFile(captor.capture());
        assertThat(captor.getValue()).isEqualTo(baseDir.resolve("sample.png").normalize());
    }

    @Test
    void legitInBasePathIsAcceptedForScreenshotSource() {
        when(cvService.analyzeScreenshot(any())).thenReturn(stubResult());

        controller.analyze(new CvAnalysisController.AnalyzeRequest("screenshot", "shots/new.png"));

        ArgumentCaptor<Path> captor = ArgumentCaptor.forClass(Path.class);
        verify(cvService).analyzeScreenshot(captor.capture());
        assertThat(captor.getValue()).isEqualTo(baseDir.resolve("shots/new.png").normalize());
    }
}
