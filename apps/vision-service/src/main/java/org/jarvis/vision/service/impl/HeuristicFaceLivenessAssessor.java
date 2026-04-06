package org.jarvis.vision.service.impl;

import lombok.RequiredArgsConstructor;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.FaceLivenessAssessment;
import org.jarvis.vision.service.FaceLivenessAssessor;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class HeuristicFaceLivenessAssessor implements FaceLivenessAssessor {

    private static final String PROVIDER = "heuristic-single-frame";

    private final VisionServiceProperties properties;

    @Override
    public String providerId() {
        return PROVIDER;
    }

    @Override
    public FaceLivenessAssessment assess(BufferedImage faceImage) {
        Map<String, String> diagnostics = new LinkedHashMap<>();
        diagnostics.put("livenessProvider", providerId());
        diagnostics.put("livenessMethod", "single-frame-texture");

        if (!properties.getLiveness().isEnabled()) {
            diagnostics.put("livenessAvailable", "false");
            diagnostics.put("livenessLimitations", "disabled");
            return new FaceLivenessAssessment(
                    false,
                    false,
                    null,
                    providerId(),
                    "Liveness heuristic disabled",
                    diagnostics);
        }

        double[][] grayscale = grayscale(faceImage);
        double contrast = standardDeviation(grayscale);
        double sharpness = averageLaplacianMagnitude(grayscale);
        double sharpnessScore = boundedScore(sharpness, properties.getLiveness().getMinimumSharpness());
        double contrastScore = boundedScore(contrast, properties.getLiveness().getMinimumContrast());
        double confidence = Math.max(0.0d, Math.min(1.0d, (sharpnessScore * 0.6d) + (contrastScore * 0.4d)));
        boolean passed = sharpness >= properties.getLiveness().getMinimumSharpness()
                && contrast >= properties.getLiveness().getMinimumContrast()
                && confidence >= properties.getLiveness().getPassThreshold();

        diagnostics.put("livenessAvailable", "true");
        diagnostics.put("livenessPassed", String.valueOf(passed));
        diagnostics.put("livenessConfidence", format(confidence));
        diagnostics.put("livenessSharpness", format(sharpness));
        diagnostics.put("livenessContrast", format(contrast));
        diagnostics.put("livenessLimitations", "heuristic single-frame quality gate; not model-backed anti-spoofing");

        return new FaceLivenessAssessment(
                true,
                passed,
                confidence,
                providerId(),
                passed
                        ? "Face passed heuristic liveness gate"
                        : "Face failed heuristic liveness gate",
                diagnostics);
    }

    @Override
    public Map<String, String> statusDetails() {
        Map<String, String> details = new LinkedHashMap<>(FaceLivenessAssessor.super.statusDetails());
        details.put("livenessMinimumSharpness", format(properties.getLiveness().getMinimumSharpness()));
        details.put("livenessMinimumContrast", format(properties.getLiveness().getMinimumContrast()));
        details.put("livenessPassThreshold", format(properties.getLiveness().getPassThreshold()));
        details.put("livenessModelBacked", "false");
        return details;
    }

    private static double[][] grayscale(BufferedImage image) {
        double[][] grayscale = new double[image.getHeight()][image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                grayscale[y][x] = red * 0.299d + green * 0.587d + blue * 0.114d;
            }
        }
        return grayscale;
    }

    private static double standardDeviation(double[][] grayscale) {
        double mean = 0.0d;
        int samples = 0;
        for (double[] row : grayscale) {
            for (double value : row) {
                mean += value;
                samples++;
            }
        }
        if (samples == 0) {
            return 0.0d;
        }
        mean /= samples;

        double variance = 0.0d;
        for (double[] row : grayscale) {
            for (double value : row) {
                double delta = value - mean;
                variance += delta * delta;
            }
        }
        return Math.sqrt(variance / samples);
    }

    private static double averageLaplacianMagnitude(double[][] grayscale) {
        if (grayscale.length < 3 || grayscale[0].length < 3) {
            return 0.0d;
        }
        double total = 0.0d;
        int samples = 0;
        for (int y = 1; y < grayscale.length - 1; y++) {
            for (int x = 1; x < grayscale[y].length - 1; x++) {
                double laplacian = 4 * grayscale[y][x]
                        - grayscale[y - 1][x]
                        - grayscale[y + 1][x]
                        - grayscale[y][x - 1]
                        - grayscale[y][x + 1];
                total += Math.abs(laplacian);
                samples++;
            }
        }
        return samples == 0 ? 0.0d : total / samples;
    }

    private static double boundedScore(double value, double minimum) {
        if (minimum <= 0.0d) {
            return 1.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value / (minimum * 2.0d)));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
