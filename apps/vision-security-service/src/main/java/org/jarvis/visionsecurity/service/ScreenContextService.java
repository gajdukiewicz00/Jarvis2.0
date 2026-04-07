package org.jarvis.visionsecurity.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.visionsecurity.model.ScreenContextEvidence;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ScreenContextService {

    private final ShellCommandRunner commandRunner;
    private final SemanticTagger semanticTagger;
    private final OcrService ocrService;

    public ScreenContextCapture collect(Path screenshotPath, Path ocrOutputPath) throws Exception {
        String activeWindowTitle = activeWindowTitle();
        String activeProcessName = activeProcessName();
        OcrService.OcrResult ocrResult = ocrService.extractText(screenshotPath, ocrOutputPath);
        List<String> tags = semanticTagger.deriveTags(activeWindowTitle, activeProcessName, ocrResult.text());
        ScreenContextEvidence evidence = new ScreenContextEvidence(
                activeWindowTitle,
                activeProcessName,
                ocrResult.text(),
                tags
        );
        return new ScreenContextCapture(evidence, ocrResult.outputPath());
    }

    public String detectDisplayServer() {
        if (System.getenv("WAYLAND_DISPLAY") != null) {
            return "wayland";
        }
        if (System.getenv("DISPLAY") != null) {
            return "x11";
        }
        return "headless";
    }

    private String activeWindowTitle() {
        try {
            if (!commandRunner.isAvailable("xdotool")) {
                return "";
            }
            ShellCommandRunner.CommandResult result = commandRunner.execute(
                    List.of("xdotool", "getactivewindow", "getwindowname"));
            return result.exitCode() == 0 ? result.output() : "";
        } catch (Exception ex) {
            return "";
        }
    }

    private String activeProcessName() {
        try {
            if (!commandRunner.isAvailable("xdotool")) {
                return "";
            }
            ShellCommandRunner.CommandResult pidResult = commandRunner.execute(
                    List.of("xdotool", "getactivewindow", "getwindowpid"));
            if (pidResult.exitCode() != 0 || pidResult.output().isBlank()) {
                return "";
            }
            String pid = pidResult.output().trim();
            ShellCommandRunner.CommandResult psResult = commandRunner.execute(
                    List.of("ps", "-p", pid, "-o", "comm="));
            return psResult.exitCode() == 0 ? psResult.output() : "";
        } catch (Exception ex) {
            return "";
        }
    }

    public record ScreenContextCapture(ScreenContextEvidence evidence, String ocrTextPath) {
    }
}
