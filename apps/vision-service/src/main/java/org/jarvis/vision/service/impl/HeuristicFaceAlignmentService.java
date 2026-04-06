package org.jarvis.vision.service.impl;

import lombok.RequiredArgsConstructor;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.FaceAlignmentResult;
import org.jarvis.vision.service.FaceAlignmentService;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class HeuristicFaceAlignmentService implements FaceAlignmentService {

    private static final String PROVIDER = "heuristic-eye-center";

    private final VisionServiceProperties properties;

    @Override
    public String providerId() {
        return PROVIDER;
    }

    @Override
    public FaceAlignmentResult align(BufferedImage faceImage) {
        BufferedImage normalizedInput = OpenCvImageUtils.copy(faceImage);
        BufferedImage squareFallback = OpenCvImageUtils.centerCropSquare(normalizedInput);
        Map<String, String> diagnostics = new LinkedHashMap<>();
        diagnostics.put("alignmentProvider", providerId());
        diagnostics.put("alignmentEnabled", String.valueOf(properties.getAlignment().isEnabled()));

        if (!properties.getAlignment().isEnabled()) {
            diagnostics.put("alignmentApplied", "false");
            diagnostics.put("alignmentMode", "square-crop-disabled");
            diagnostics.put("alignmentHeuristicLimited", "true");
            return new FaceAlignmentResult(
                    true,
                    false,
                    providerId(),
                    "square-crop-disabled",
                    "Alignment disabled; using centered square crop",
                    squareFallback,
                    diagnostics);
        }

        EyePair eyePair = detectEyePair(normalizedInput);
        if (!eyePair.valid()) {
            diagnostics.put("alignmentApplied", "false");
            diagnostics.put("alignmentMode", "square-crop-fallback");
            diagnostics.put("alignmentHeuristicLimited", "true");
            diagnostics.put("alignmentReason", eyePair.reason());
            return new FaceAlignmentResult(
                    true,
                    false,
                    providerId(),
                    "square-crop-fallback",
                    eyePair.reason(),
                    squareFallback,
                    diagnostics);
        }

        double rotationDegrees = Math.toDegrees(Math.atan2(
                eyePair.right().getY() - eyePair.left().getY(),
                eyePair.right().getX() - eyePair.left().getX()));
        diagnostics.put("alignmentRotationDegrees", format(rotationDegrees));
        diagnostics.put("alignmentEyeSeparationRatio", format(eyePair.separationRatio()));
        diagnostics.put("alignmentLeftEye", formatPoint(eyePair.left()));
        diagnostics.put("alignmentRightEye", formatPoint(eyePair.right()));

        if (Math.abs(rotationDegrees) > properties.getAlignment().getMaximumRollAngleDegrees()) {
            diagnostics.put("alignmentApplied", "false");
            diagnostics.put("alignmentMode", "square-crop-roll-limit");
            diagnostics.put("alignmentHeuristicLimited", "true");
            diagnostics.put("alignmentReason", "Estimated eye roll exceeded configured maximum");
            return new FaceAlignmentResult(
                    true,
                    false,
                    providerId(),
                    "square-crop-roll-limit",
                    "Estimated eye roll exceeded configured maximum; using square crop fallback",
                    squareFallback,
                    diagnostics);
        }

        BufferedImage rotated = rotate(normalizedInput, -rotationDegrees);
        BufferedImage aligned = OpenCvImageUtils.centerCropSquare(rotated);
        diagnostics.put("alignmentApplied", "true");
        diagnostics.put("alignmentMode", Math.abs(rotationDegrees) < 0.5d
                ? "square-crop-eye-normalized"
                : "eye-rotation-square-crop");
        diagnostics.put("alignmentHeuristicLimited", "true");
        return new FaceAlignmentResult(
                true,
                true,
                providerId(),
                diagnostics.get("alignmentMode"),
                "Aligned face crop using heuristic eye-center normalization",
                aligned,
                diagnostics);
    }

    @Override
    public String cacheKey() {
        return providerId()
                + "::enabled=" + properties.getAlignment().isEnabled()
                + "::minEyeSeparation=" + format(properties.getAlignment().getMinimumEyeSeparationRatio())
                + "::maxRoll=" + format(properties.getAlignment().getMaximumRollAngleDegrees());
    }

    @Override
    public Map<String, String> statusDetails() {
        Map<String, String> details = new LinkedHashMap<>(FaceAlignmentService.super.statusDetails());
        details.put("alignmentMinimumEyeSeparationRatio", format(properties.getAlignment().getMinimumEyeSeparationRatio()));
        details.put("alignmentMaximumRollAngleDegrees", format(properties.getAlignment().getMaximumRollAngleDegrees()));
        details.put("alignmentLandmarkMode", "false");
        details.put("alignmentLimitations", "single-frame heuristic eye localization");
        return details;
    }

    private EyePair detectEyePair(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < 16 || height < 16) {
            return EyePair.invalid("Face crop too small for alignment");
        }

        int windowWidth = Math.max(3, width / 8);
        int windowHeight = Math.max(3, height / 8);
        double[][] grayscale = grayscale(image);

        EyeCandidate left = findDarkestWindow(
                grayscale,
                Math.max(0, width / 10),
                Math.max(1, width / 2 - windowWidth),
                Math.max(0, height / 10),
                Math.max(1, (int) Math.round(height * 0.55d) - windowHeight),
                windowWidth,
                windowHeight);
        EyeCandidate right = findDarkestWindow(
                grayscale,
                Math.max(0, width / 2),
                Math.max(width / 2 + 1, width - width / 10 - windowWidth),
                Math.max(0, height / 10),
                Math.max(1, (int) Math.round(height * 0.55d) - windowHeight),
                windowWidth,
                windowHeight);

        if (left == null || right == null) {
            return EyePair.invalid("Failed to localize eye candidates");
        }

        double globalMean = meanIntensity(grayscale);
        double separationRatio = left.center.distance(right.center) / width;
        boolean contrastOk = left.intensity < globalMean - 8.0d && right.intensity < globalMean - 8.0d;
        boolean geometryOk = separationRatio >= properties.getAlignment().getMinimumEyeSeparationRatio()
                && Math.abs(left.center.getY() - right.center.getY()) <= (height * 0.20d);

        if (!contrastOk || !geometryOk) {
            return EyePair.invalid("Eye candidates did not meet contrast/geometry constraints");
        }

        return new EyePair(left.center, right.center, separationRatio, "");
    }

    private static EyeCandidate findDarkestWindow(double[][] grayscale,
                                                  int startX,
                                                  int endX,
                                                  int startY,
                                                  int endY,
                                                  int windowWidth,
                                                  int windowHeight) {
        EyeCandidate best = null;
        for (int y = startY; y <= endY; y++) {
            for (int x = startX; x <= endX; x++) {
                double intensity = averageIntensity(grayscale, x, y, windowWidth, windowHeight);
                if (best == null || intensity < best.intensity) {
                    best = new EyeCandidate(
                            new Point2D.Double(x + (windowWidth / 2.0d), y + (windowHeight / 2.0d)),
                            intensity);
                }
            }
        }
        return best;
    }

    private static double averageIntensity(double[][] grayscale, int startX, int startY, int width, int height) {
        double total = 0.0d;
        int samples = 0;
        for (int y = startY; y < startY + height && y < grayscale.length; y++) {
            for (int x = startX; x < startX + width && x < grayscale[y].length; x++) {
                total += grayscale[y][x];
                samples++;
            }
        }
        return samples == 0 ? 255.0d : total / samples;
    }

    private static double meanIntensity(double[][] grayscale) {
        double total = 0.0d;
        int samples = 0;
        for (double[] row : grayscale) {
            for (double value : row) {
                total += value;
                samples++;
            }
        }
        return samples == 0 ? 0.0d : total / samples;
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

    private static BufferedImage rotate(BufferedImage source, double angleDegrees) {
        BufferedImage rotated = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = rotated.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(averageBorderColor(source));
            graphics.fillRect(0, 0, rotated.getWidth(), rotated.getHeight());
            AffineTransform transform = AffineTransform.getRotateInstance(
                    Math.toRadians(angleDegrees),
                    source.getWidth() / 2.0d,
                    source.getHeight() / 2.0d);
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        return rotated;
    }

    private static Color averageBorderColor(BufferedImage image) {
        long red = 0L;
        long green = 0L;
        long blue = 0L;
        long samples = 0L;
        for (int x = 0; x < image.getWidth(); x++) {
            int top = image.getRGB(x, 0);
            int bottom = image.getRGB(x, image.getHeight() - 1);
            red += ((top >> 16) & 0xFF) + ((bottom >> 16) & 0xFF);
            green += ((top >> 8) & 0xFF) + ((bottom >> 8) & 0xFF);
            blue += (top & 0xFF) + (bottom & 0xFF);
            samples += 2;
        }
        for (int y = 1; y < image.getHeight() - 1; y++) {
            int left = image.getRGB(0, y);
            int right = image.getRGB(image.getWidth() - 1, y);
            red += ((left >> 16) & 0xFF) + ((right >> 16) & 0xFF);
            green += ((left >> 8) & 0xFF) + ((right >> 8) & 0xFF);
            blue += (left & 0xFF) + (right & 0xFF);
            samples += 2;
        }
        if (samples == 0L) {
            return Color.BLACK;
        }
        return new Color((int) (red / samples), (int) (green / samples), (int) (blue / samples));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String formatPoint(Point2D point) {
        return format(point.getX()) + "," + format(point.getY());
    }

    private record EyeCandidate(Point2D center, double intensity) {
    }

    private record EyePair(Point2D left, Point2D right, double separationRatio, String reason) {

        static EyePair invalid(String reason) {
            return new EyePair(new Point2D.Double(), new Point2D.Double(), 0.0d, reason);
        }

        boolean valid() {
            return reason == null || reason.isBlank();
        }
    }
}
