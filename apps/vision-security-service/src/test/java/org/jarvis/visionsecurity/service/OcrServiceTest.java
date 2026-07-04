package org.jarvis.visionsecurity.service;

import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OcrServiceTest {

    private final ShellCommandRunner runner = mock(ShellCommandRunner.class);
    private final VisionSecurityProperties properties = new VisionSecurityProperties();
    private final OcrService service = new OcrService(runner, properties);

    @Test
    void capabilityStatusReportsUnavailableWhenTesseractMissing() {
        when(runner.isAvailable("tesseract")).thenReturn(false);

        CapabilityStatus status = service.capabilityStatus();

        assertThat(status.state()).isEqualTo("UNAVAILABLE");
        assertThat(status.detail()).contains("tesseract-ocr");
    }

    @Test
    void capabilityStatusReportsAvailableWhenTesseractPresent() {
        when(runner.isAvailable("tesseract")).thenReturn(true);

        CapabilityStatus status = service.capabilityStatus();

        assertThat(status.state()).isEqualTo("AVAILABLE");
        assertThat(status.detail()).contains("tesseract");
    }

    @Test
    void extractTextWritesDegradedMessageWhenTesseractMissing(@TempDir Path tmp) throws Exception {
        when(runner.isAvailable("tesseract")).thenReturn(false);
        Path image = tmp.resolve("frame.png");
        Files.writeString(image, "fake png bytes");
        Path output = tmp.resolve("out/ocr.txt");

        OcrService.OcrResult result = service.extractText(image, output);

        assertThat(result.extracted()).isFalse();
        assertThat(result.detail()).contains("OCR unavailable");
        assertThat(result.text()).isEmpty();
        assertThat(Files.readString(output, StandardCharsets.UTF_8))
                .contains("OCR unavailable");
    }

    @Test
    void extractTextReturnsTextWhenTesseractSucceeds(@TempDir Path tmp) throws Exception {
        Path image = tmp.resolve("frame.png");
        Files.writeString(image, "fake png bytes");
        Path output = tmp.resolve("out/ocr.txt");

        when(runner.isAvailable("tesseract")).thenReturn(true);
        when(runner.execute(eq(List.of(
                "tesseract", image.toString(), "stdout",
                "-l", properties.getScreen().getOcrLanguage(),
                "--psm", "6"))))
                .thenReturn(new ShellCommandRunner.CommandResult(0, "Hello world"));

        OcrService.OcrResult result = service.extractText(image, output);

        assertThat(result.extracted()).isTrue();
        assertThat(result.text()).isEqualTo("Hello world");
        assertThat(Files.readString(output, StandardCharsets.UTF_8)).isEqualTo("Hello world");
    }

    @Test
    void extractTextWritesFailureWhenTesseractExitsNonZero(@TempDir Path tmp) throws Exception {
        Path image = tmp.resolve("frame.png");
        Files.writeString(image, "fake png bytes");
        Path output = tmp.resolve("out/ocr.txt");

        when(runner.isAvailable("tesseract")).thenReturn(true);
        when(runner.execute(eq(List.of(
                "tesseract", image.toString(), "stdout",
                "-l", properties.getScreen().getOcrLanguage(),
                "--psm", "6"))))
                .thenReturn(new ShellCommandRunner.CommandResult(1, "image not readable"));

        OcrService.OcrResult result = service.extractText(image, output);

        assertThat(result.extracted()).isFalse();
        assertThat(result.detail()).contains("OCR failed").contains("image not readable");
        assertThat(Files.readString(output, StandardCharsets.UTF_8)).contains("image not readable");
    }
}
