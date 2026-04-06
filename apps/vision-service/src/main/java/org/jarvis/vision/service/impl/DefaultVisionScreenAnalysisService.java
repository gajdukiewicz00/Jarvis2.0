package org.jarvis.vision.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.vision.VisionScreenAnalysisRequest;
import org.jarvis.common.vision.VisionScreenAnalysisResponse;
import org.jarvis.common.vision.VisionScreenCategory;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.VisionScreenAnalysisService;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultVisionScreenAnalysisService implements VisionScreenAnalysisService {

    private final VisionServiceProperties properties;

    @Override
    public VisionScreenAnalysisResponse analyze(VisionScreenAnalysisRequest request) {
        if (!properties.getScreen().isEnabled()) {
            return new VisionScreenAnalysisResponse(
                    false,
                    VisionScreenCategory.UNAVAILABLE,
                    "Screen analysis disabled",
                    null,
                    false,
                    null,
                    false,
                    Map.of(
                            "screenAnalysisAvailable", "false",
                            "screenAnalysisMethod", "disabled"));
        }
        if (request.imageBytes().length == 0) {
            return new VisionScreenAnalysisResponse(
                    false,
                    VisionScreenCategory.UNAVAILABLE,
                    "Image payload is required",
                    null,
                    false,
                    null,
                    false,
                    Map.of(
                            "screenAnalysisAvailable", "true",
                            "screenAnalysisMethod", "heuristic-foundation"));
        }

        try {
            BufferedImage image = OpenCvImageUtils.decode(request.imageBytes());
            ScreenMetrics metrics = metrics(image);
            CategoryPrediction categoryPrediction = predictCategory(metrics);
            boolean ocrReady = metrics.contrast() >= properties.getScreen().getOcrReadyContrastThreshold()
                    && metrics.textEdgeDensity() >= properties.getScreen().getTextEdgeDensityThreshold() * 0.60d;
            double sensitiveConfidence = sensitiveConfidence(categoryPrediction.category(), metrics);
            boolean sensitive = sensitiveConfidence >= properties.getScreen().getSensitiveThreshold();

            Map<String, String> diagnostics = new LinkedHashMap<>();
            diagnostics.put("screenAnalysisAvailable", "true");
            diagnostics.put("screenAnalysisMethod", "heuristic-foundation");
            diagnostics.put("screenAnalysisLimitations", "single-frame heuristics; OCR/classifier models not integrated yet");
            diagnostics.put("screenTextEdgeDensity", format(metrics.textEdgeDensity()));
            diagnostics.put("screenContrast", format(metrics.contrast()));
            diagnostics.put("screenColorfulness", format(metrics.colorfulness()));
            diagnostics.put("screenDarkPixelRatio", format(metrics.darkRatio()));
            diagnostics.put("screenBrightPixelRatio", format(metrics.brightRatio()));
            diagnostics.put("screenOcrReady", String.valueOf(ocrReady));
            diagnostics.put("screenSensitiveConfidence", format(sensitiveConfidence));

            return new VisionScreenAnalysisResponse(
                    true,
                    categoryPrediction.category(),
                    categoryPrediction.message(),
                    categoryPrediction.confidence(),
                    sensitive,
                    sensitiveConfidence,
                    ocrReady,
                    diagnostics);
        } catch (Exception exception) {
            log.warn("Screen analysis failed: requestId={}, source={}, message={}",
                    request.requestId(), request.source(), exception.getMessage());
            return new VisionScreenAnalysisResponse(
                    false,
                    VisionScreenCategory.UNAVAILABLE,
                    exception.getMessage(),
                    null,
                    false,
                    null,
                    false,
                    Map.of(
                            "screenAnalysisAvailable", "true",
                            "screenAnalysisMethod", "heuristic-foundation"));
        }
    }

    private CategoryPrediction predictCategory(ScreenMetrics metrics) {
        if (metrics.textEdgeDensity() >= properties.getScreen().getTextEdgeDensityThreshold()
                && metrics.darkRatio() >= 0.45d
                && metrics.colorfulness() < 45.0d) {
            return new CategoryPrediction(VisionScreenCategory.TERMINAL, 0.80d,
                    "Dark, text-dense screen resembles a terminal or code workspace");
        }
        if (metrics.textEdgeDensity() >= properties.getScreen().getTextEdgeDensityThreshold()
                && metrics.brightRatio() >= 0.55d) {
            return new CategoryPrediction(VisionScreenCategory.DOCUMENT, 0.78d,
                    "Bright, text-dense screen resembles a document or form");
        }
        if (metrics.textEdgeDensity() >= properties.getScreen().getTextEdgeDensityThreshold() * 0.90d
                && metrics.colorfulness() >= 35.0d
                && metrics.brightRatio() >= 0.20d
                && metrics.darkRatio() <= 0.45d) {
            return new CategoryPrediction(VisionScreenCategory.CHAT, 0.68d,
                    "Mixed-color text layout resembles chat or messaging");
        }
        if (metrics.textEdgeDensity() <= properties.getScreen().getTextEdgeDensityThreshold() * 0.70d
                && metrics.colorfulness() >= 55.0d) {
            return new CategoryPrediction(VisionScreenCategory.MEDIA, 0.72d,
                    "Low text density and high colorfulness resemble media content");
        }
        if (metrics.textEdgeDensity() >= properties.getScreen().getTextEdgeDensityThreshold() * 0.70d) {
            return new CategoryPrediction(VisionScreenCategory.BROWSER, 0.55d,
                    "Moderately text-dense layout resembles a browser/application window");
        }
        return new CategoryPrediction(VisionScreenCategory.UNKNOWN, 0.35d,
                "Screen category is currently ambiguous");
    }

    private static double sensitiveConfidence(VisionScreenCategory category, ScreenMetrics metrics) {
        double base = switch (category) {
            case TERMINAL -> 0.80d;
            case DOCUMENT -> 0.72d;
            case CHAT -> 0.65d;
            case BROWSER -> 0.45d;
            case MEDIA, UNKNOWN, UNAVAILABLE -> 0.20d;
        };
        double textBoost = Math.min(0.20d, metrics.textEdgeDensity() * 0.80d);
        return Math.max(0.0d, Math.min(1.0d, base + textBoost));
    }

    private static ScreenMetrics metrics(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width == 0 || height == 0) {
            return new ScreenMetrics(0.0d, 0.0d, 0.0d, 0.0d, 0.0d);
        }

        double[] intensities = new double[width * height];
        double colorfulness = 0.0d;
        int darkPixels = 0;
        int brightPixels = 0;
        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                double intensity = red * 0.299d + green * 0.587d + blue * 0.114d;
                intensities[index++] = intensity;
                double rg = Math.abs(red - green);
                double yb = Math.abs((red + green) / 2.0d - blue);
                colorfulness += Math.sqrt((rg * rg) + (yb * yb));
                if (intensity <= 50.0d) {
                    darkPixels++;
                }
                if (intensity >= 205.0d) {
                    brightPixels++;
                }
            }
        }

        double contrast = standardDeviation(intensities);
        double edgeDensity = edgeDensity(image);
        double pixelCount = width * height;
        return new ScreenMetrics(
                edgeDensity,
                contrast,
                colorfulness / pixelCount,
                darkPixels / pixelCount,
                brightPixels / pixelCount);
    }

    private static double edgeDensity(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < 2 || height < 2) {
            return 0.0d;
        }

        int edges = 0;
        int samples = 0;
        for (int y = 1; y < height; y++) {
            for (int x = 1; x < width; x++) {
                double current = intensity(image.getRGB(x, y));
                double left = intensity(image.getRGB(x - 1, y));
                double up = intensity(image.getRGB(x, y - 1));
                double gradient = Math.abs(current - left) + Math.abs(current - up);
                if (gradient >= 45.0d) {
                    edges++;
                }
                samples++;
            }
        }
        return samples == 0 ? 0.0d : edges / (double) samples;
    }

    private static double standardDeviation(double[] values) {
        if (values.length == 0) {
            return 0.0d;
        }
        double mean = 0.0d;
        for (double value : values) {
            mean += value;
        }
        mean /= values.length;

        double variance = 0.0d;
        for (double value : values) {
            double delta = value - mean;
            variance += delta * delta;
        }
        return Math.sqrt(variance / values.length);
    }

    private static double intensity(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return red * 0.299d + green * 0.587d + blue * 0.114d;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private record ScreenMetrics(
            double textEdgeDensity,
            double contrast,
            double colorfulness,
            double darkRatio,
            double brightRatio) {
    }

    private record CategoryPrediction(
            VisionScreenCategory category,
            double confidence,
            String message) {
    }
}
