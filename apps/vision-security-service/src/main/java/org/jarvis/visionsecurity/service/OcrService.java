package org.jarvis.visionsecurity.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OcrService {

    private final ShellCommandRunner commandRunner;
    private final VisionSecurityProperties properties;

    public CapabilityStatus capabilityStatus() {
        if (!commandRunner.isAvailable("tesseract")) {
            return new CapabilityStatus("UNAVAILABLE", "Install `tesseract-ocr` to enable OCR extraction");
        }
        return new CapabilityStatus("AVAILABLE", "Using local tesseract CLI");
    }

    public OcrResult extractText(Path imagePath, Path outputPath) throws Exception {
        Files.createDirectories(outputPath.getParent());

        if (!commandRunner.isAvailable("tesseract")) {
            String message = "OCR unavailable: tesseract is not installed";
            Files.writeString(outputPath, message, StandardCharsets.UTF_8);
            return new OcrResult("", false, message, outputPath.toString());
        }

        ShellCommandRunner.CommandResult result = commandRunner.execute(List.of(
                "tesseract",
                imagePath.toString(),
                "stdout",
                "-l",
                properties.getScreen().getOcrLanguage(),
                "--psm",
                "6"));

        if (result.exitCode() != 0) {
            String message = "OCR failed: " + result.output();
            Files.writeString(outputPath, message, StandardCharsets.UTF_8);
            return new OcrResult("", false, message, outputPath.toString());
        }

        Files.writeString(outputPath, result.output(), StandardCharsets.UTF_8);
        return new OcrResult(result.output(), true, "OCR extracted", outputPath.toString());
    }

    public record OcrResult(String text, boolean extracted, String detail, String outputPath) {
    }
}
