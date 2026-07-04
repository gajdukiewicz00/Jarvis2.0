package org.jarvis.visionsecurity.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.jarvis.visionsecurity.model.CvBlock;
import org.jarvis.visionsecurity.model.RectBox;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OcrService {

    public static final String ENGINE = "tesseract";

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

    /**
     * Run tesseract with TSV output to recover per-word bounding boxes and
     * confidence scores. Words are grouped back into lines (matching
     * tesseract's own block/par/line indices) so callers receive a list of
     * line-level blocks with their concatenated text and union bbox.
     *
     * @throws OcrUnavailableException when the tesseract binary is missing
     * @throws OcrExecutionException   when tesseract exits non-zero
     */
    public StructuredOcrResult extractStructured(Path imagePath) throws Exception {
        if (!commandRunner.isAvailable("tesseract")) {
            throw new OcrUnavailableException(
                    "tesseract binary not found on PATH; install `tesseract-ocr` to enable OCR");
        }
        String language = properties.getScreen().getOcrLanguage();
        ShellCommandRunner.CommandResult result = commandRunner.execute(List.of(
                "tesseract",
                imagePath.toString(),
                "stdout",
                "-l",
                language,
                "--psm",
                "6",
                "tsv"));
        if (result.exitCode() != 0) {
            throw new OcrExecutionException(
                    "tesseract exit code " + result.exitCode() + ": " + result.output());
        }
        List<CvBlock> blocks = parseTsv(result.output());
        String text = blocksToText(blocks);
        return new StructuredOcrResult(blocks, text, language);
    }

    static List<CvBlock> parseTsv(String tsvBody) {
        if (tsvBody == null || tsvBody.isBlank()) {
            return List.of();
        }
        String[] lines = tsvBody.split("\\r?\\n");
        if (lines.length <= 1) {
            return List.of();
        }
        record LineKey(int block, int par, int line) {}
        java.util.LinkedHashMap<LineKey, java.util.List<TsvWord>> grouped = new java.util.LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String row = lines[i];
            if (row.isEmpty()) continue;
            String[] cols = row.split("\t", -1);
            if (cols.length < 12) continue;
            int level;
            try { level = Integer.parseInt(cols[0]); } catch (NumberFormatException ex) { continue; }
            if (level != 5) continue; // word level
            String text = cols[11];
            if (text == null || text.isBlank()) continue;
            try {
                int block = Integer.parseInt(cols[2]);
                int par = Integer.parseInt(cols[3]);
                int line = Integer.parseInt(cols[4]);
                int left = Integer.parseInt(cols[6]);
                int top = Integer.parseInt(cols[7]);
                int width = Integer.parseInt(cols[8]);
                int height = Integer.parseInt(cols[9]);
                double conf = Double.parseDouble(cols[10]);
                grouped.computeIfAbsent(new LineKey(block, par, line), k -> new java.util.ArrayList<>())
                        .add(new TsvWord(text, conf, left, top, width, height));
            } catch (NumberFormatException ignore) {
                // skip malformed row
            }
        }
        List<CvBlock> out = new ArrayList<>(grouped.size());
        for (var entry : grouped.entrySet()) {
            java.util.List<TsvWord> words = entry.getValue();
            if (words.isEmpty()) continue;
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            double confSum = 0.0;
            double confCount = 0.0;
            StringBuilder textBuilder = new StringBuilder();
            for (TsvWord word : words) {
                if (textBuilder.length() > 0) textBuilder.append(' ');
                textBuilder.append(word.text);
                minX = Math.min(minX, word.left);
                minY = Math.min(minY, word.top);
                maxX = Math.max(maxX, word.left + word.width);
                maxY = Math.max(maxY, word.top + word.height);
                if (word.conf >= 0) {
                    confSum += word.conf;
                    confCount += 1;
                }
            }
            double meanConf = confCount > 0 ? confSum / confCount : -1.0;
            out.add(new CvBlock(
                    textBuilder.toString(),
                    Math.round(meanConf * 100.0) / 100.0,
                    new RectBox(minX, minY, maxX - minX, maxY - minY)));
        }
        return out;
    }

    private static String blocksToText(List<CvBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(blocks.get(i).text());
        }
        return sb.toString();
    }

    public record OcrResult(String text, boolean extracted, String detail, String outputPath) {
    }

    public record StructuredOcrResult(List<CvBlock> blocks, String text, String language) {
    }

    private record TsvWord(String text, double conf, int left, int top, int width, int height) {
    }

    public static class OcrUnavailableException extends RuntimeException {
        public OcrUnavailableException(String message) { super(message); }
    }

    public static class OcrExecutionException extends RuntimeException {
        public OcrExecutionException(String message) { super(message); }
    }
}
