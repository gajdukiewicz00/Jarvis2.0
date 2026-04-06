package org.jarvis.vision.pipeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.vision.VisionFaceRegion;
import org.jarvis.common.vision.VisionPipelineStage;
import org.jarvis.vision.config.VisionServiceProperties;
import org.jarvis.vision.service.FaceDetectionProvider;
import org.jarvis.vision.service.impl.OpenCvImageUtils;
import org.jarvis.vision.service.impl.OpenCvRuntime;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultVisionImagePipelineService implements VisionImagePipelineService {

    private final VisionServiceProperties properties;
    private final OpenCvRuntime openCvRuntime;

    @Override
    public VisionPipelineExecution process(BufferedImage originalImage, FaceDetectionProvider faceDetectionProvider) {
        List<VisionPipelineArtifactImage> artifacts = new ArrayList<>();
        List<VisionPipelineStageSnapshot> stages = new ArrayList<>();
        Map<String, String> diagnostics = new LinkedHashMap<>();

        artifacts.add(new VisionPipelineArtifactImage(
                VisionPipelineStage.ORIGINAL,
                originalImage,
                "Original request image"));
        stages.add(new VisionPipelineStageSnapshot(
                VisionPipelineStage.ORIGINAL,
                "Original input image",
                Map.of(
                        "width", String.valueOf(originalImage.getWidth()),
                        "height", String.valueOf(originalImage.getHeight())),
                List.of()));

        if (!openCvRuntime.isAvailable()) {
            String message = "OpenCV runtime unavailable: " + openCvRuntime.failureMessage();
            diagnostics.put("pipelineOperational", "false");
            diagnostics.put("pipelineMessage", message);
            FaceDetectionProvider.DetectionResult detectionResult =
                    new FaceDetectionProvider.DetectionResult(false, "", message, List.of());
            stages.add(unavailableStage(VisionPipelineStage.ENHANCEMENT, message));
            stages.add(unavailableStage(VisionPipelineStage.SEGMENTATION, message));
            stages.add(unavailableStage(VisionPipelineStage.CLEANING, message));
            stages.add(unavailableStage(VisionPipelineStage.DETECTION, message));
            return new VisionPipelineExecution(artifacts, stages, detectionResult, diagnostics);
        }

        Mat originalMat = null;
        Mat gray = null;
        Mat enhanced = null;
        Mat thresholdMask = null;
        Mat edgeMask = null;
        Mat segmentationMask = null;
        Mat cleanedMask = null;
        Mat overlay = null;
        try {
            originalMat = OpenCvImageUtils.toMat(originalImage);
            gray = new Mat();
            Imgproc.cvtColor(originalMat, gray, Imgproc.COLOR_BGR2GRAY);

            enhanced = enhance(gray);
            BufferedImage enhancedImage = OpenCvImageUtils.fromMat(enhanced);
            artifacts.add(new VisionPipelineArtifactImage(
                    VisionPipelineStage.ENHANCEMENT,
                    enhancedImage,
                    "Grayscale + histogram equalization + gamma correction + blur"));
            stages.add(new VisionPipelineStageSnapshot(
                    VisionPipelineStage.ENHANCEMENT,
                    "Enhanced grayscale image",
                    Map.of(
                            "gamma", String.valueOf(properties.getPipeline().getGamma()),
                            "blurKernelSize", String.valueOf(properties.getPipeline().getBlurKernelSize())),
                    List.of()));

            thresholdMask = new Mat();
            Imgproc.adaptiveThreshold(
                    enhanced,
                    thresholdMask,
                    255,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY,
                    properties.getPipeline().getAdaptiveThresholdBlockSize(),
                    properties.getPipeline().getAdaptiveThresholdConstant());
            edgeMask = new Mat();
            Imgproc.Canny(
                    enhanced,
                    edgeMask,
                    properties.getPipeline().getCannyLowThreshold(),
                    properties.getPipeline().getCannyHighThreshold());
            segmentationMask = new Mat();
            Core.bitwise_or(thresholdMask, edgeMask, segmentationMask);
            BufferedImage segmentationImage = OpenCvImageUtils.fromMat(segmentationMask);
            artifacts.add(new VisionPipelineArtifactImage(
                    VisionPipelineStage.SEGMENTATION,
                    segmentationImage,
                    "Adaptive threshold and edge-based segmentation mask"));
            stages.add(new VisionPipelineStageSnapshot(
                    VisionPipelineStage.SEGMENTATION,
                    "Segmentation mask generated from thresholding and edges",
                    maskMetrics(segmentationMask, "adaptive-threshold+canny"),
                    List.of()));

            cleanedMask = clean(segmentationMask);
            BufferedImage cleanedImage = OpenCvImageUtils.fromMat(cleanedMask);
            artifacts.add(new VisionPipelineArtifactImage(
                    VisionPipelineStage.CLEANING,
                    cleanedImage,
                    "Morphologically cleaned mask"));
            stages.add(new VisionPipelineStageSnapshot(
                    VisionPipelineStage.CLEANING,
                    "Cleaned mask after opening and closing",
                    maskMetrics(cleanedMask, "morphology-open-close"),
                    List.of()));

            List<VisionFaceRegion> candidateRegions = contourCandidates(cleanedMask);
            FaceDetectionProvider.DetectionResult detectionResult = faceDetectionProvider.detectFaces(originalImage);
            overlay = originalMat.clone();
            drawDetectionOverlay(overlay, candidateRegions, detectionResult.faces());
            BufferedImage detectionImage = OpenCvImageUtils.fromMat(overlay);
            artifacts.add(new VisionPipelineArtifactImage(
                    VisionPipelineStage.DETECTION,
                    detectionImage,
                    "Detection overlay with contour candidates and verified face boxes"));
            stages.add(new VisionPipelineStageSnapshot(
                    VisionPipelineStage.DETECTION,
                    detectionResult.operational()
                            ? detectionResult.message()
                            : "Detection unavailable",
                    detectionMetrics(candidateRegions, detectionResult, faceDetectionProvider),
                    detectionResult.faces()));

            diagnostics.put("pipelineOperational", String.valueOf(detectionResult.operational()));
            diagnostics.put("pipelineDetectedFaceCount", String.valueOf(detectionResult.faces().size()));
            diagnostics.put("pipelineCandidateRegionCount", String.valueOf(candidateRegions.size()));
            diagnostics.put("pipelineStageArtifactCount", String.valueOf(artifacts.size()));

            return new VisionPipelineExecution(artifacts, stages, detectionResult, diagnostics);
        } catch (Exception exception) {
            log.warn("Vision pipeline failed: {}", exception.getMessage());
            diagnostics.put("pipelineOperational", "false");
            diagnostics.put("pipelineMessage", exception.getMessage());
            stages.add(unavailableStage(VisionPipelineStage.ENHANCEMENT, exception.getMessage()));
            stages.add(unavailableStage(VisionPipelineStage.SEGMENTATION, exception.getMessage()));
            stages.add(unavailableStage(VisionPipelineStage.CLEANING, exception.getMessage()));
            stages.add(unavailableStage(VisionPipelineStage.DETECTION, exception.getMessage()));
            return new VisionPipelineExecution(
                    artifacts,
                    stages,
                    new FaceDetectionProvider.DetectionResult(false, "", exception.getMessage(), List.of()),
                    diagnostics);
        } finally {
            release(originalMat, gray, enhanced, thresholdMask, edgeMask, segmentationMask, cleanedMask, overlay);
        }
    }

    private Mat enhance(Mat grayscale) {
        Mat equalized = new Mat();
        Mat gammaCorrected = new Mat();
        Mat enhanced = new Mat();
        Mat lookupTable = new Mat(1, 256, CvType.CV_8U);
        try {
            Imgproc.equalizeHist(grayscale, equalized);
            lookupTable.put(0, 0, gammaLookupTable(properties.getPipeline().getGamma()));
            Core.LUT(equalized, lookupTable, gammaCorrected);
            if (properties.getPipeline().getBlurKernelSize() > 1) {
                Imgproc.GaussianBlur(
                        gammaCorrected,
                        enhanced,
                        new Size(properties.getPipeline().getBlurKernelSize(), properties.getPipeline().getBlurKernelSize()),
                        0.0d);
            } else {
                gammaCorrected.copyTo(enhanced);
            }
            return enhanced;
        } finally {
            equalized.release();
            gammaCorrected.release();
            lookupTable.release();
        }
    }

    private Mat clean(Mat segmentationMask) {
        Mat opened = new Mat();
        Mat cleaned = new Mat();
        Mat kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE,
                new Size(
                        properties.getPipeline().getMorphologyKernelSize(),
                        properties.getPipeline().getMorphologyKernelSize()));
        try {
            Imgproc.morphologyEx(segmentationMask, opened, Imgproc.MORPH_OPEN, kernel);
            Imgproc.morphologyEx(opened, cleaned, Imgproc.MORPH_CLOSE, kernel);
            return cleaned;
        } finally {
            opened.release();
            kernel.release();
        }
    }

    private List<VisionFaceRegion> contourCandidates(Mat cleanedMask) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Mat maskForContours = cleanedMask.clone();
        try {
            Imgproc.findContours(maskForContours, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            List<VisionFaceRegion> regions = new ArrayList<>();
            for (MatOfPoint contour : contours) {
                double area = Imgproc.contourArea(contour);
                if (area < properties.getPipeline().getMinimumCandidateAreaPixels()) {
                    continue;
                }
                Rect rect = Imgproc.boundingRect(contour);
                regions.add(new VisionFaceRegion(rect.x, rect.y, rect.width, rect.height));
            }
            regions.sort(Comparator.comparingInt(region -> -region.width() * region.height()));
            return regions;
        } finally {
            maskForContours.release();
            hierarchy.release();
            contours.forEach(Mat::release);
        }
    }

    private static void drawDetectionOverlay(Mat overlay,
                                             List<VisionFaceRegion> candidateRegions,
                                             List<VisionFaceRegion> detectedFaces) {
        Scalar candidateColor = new Scalar(0, 215, 255);
        Scalar faceColor = new Scalar(0, 255, 0);
        for (VisionFaceRegion candidateRegion : candidateRegions) {
            Imgproc.rectangle(
                    overlay,
                    new Point(candidateRegion.x(), candidateRegion.y()),
                    new Point(candidateRegion.x() + candidateRegion.width(), candidateRegion.y() + candidateRegion.height()),
                    candidateColor,
                    1);
        }
        for (VisionFaceRegion detectedFace : detectedFaces) {
            Imgproc.rectangle(
                    overlay,
                    new Point(detectedFace.x(), detectedFace.y()),
                    new Point(detectedFace.x() + detectedFace.width(), detectedFace.y() + detectedFace.height()),
                    faceColor,
                    2);
        }
    }

    private static VisionPipelineStageSnapshot unavailableStage(VisionPipelineStage stage, String message) {
        return new VisionPipelineStageSnapshot(
                stage,
                message,
                Map.of("operational", "false"),
                List.of());
    }

    private Map<String, String> maskMetrics(Mat mask, String method) {
        int nonZeroPixels = Core.countNonZero(mask);
        double coveragePercent = mask.total() == 0 ? 0.0d : (nonZeroPixels * 100.0d) / mask.total();
        return Map.of(
                "method", method,
                "nonZeroPixels", String.valueOf(nonZeroPixels),
                "coveragePercent", String.format(java.util.Locale.ROOT, "%.2f", coveragePercent));
    }

    private Map<String, String> detectionMetrics(List<VisionFaceRegion> candidateRegions,
                                                 FaceDetectionProvider.DetectionResult detectionResult,
                                                 FaceDetectionProvider faceDetectionProvider) {
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("detectorProvider", faceDetectionProvider.providerId());
        metrics.put("candidateRegionCount", String.valueOf(candidateRegions.size()));
        metrics.put("detectedFaceCount", String.valueOf(detectionResult.faces().size()));
        metrics.put("operational", String.valueOf(detectionResult.operational()));
        return metrics;
    }

    private static byte[] gammaLookupTable(double gamma) {
        byte[] lookup = new byte[256];
        double exponent = gamma <= 0.0d ? 1.0d : 1.0d / gamma;
        for (int value = 0; value < 256; value++) {
            int corrected = (int) Math.round(Math.pow(value / 255.0d, exponent) * 255.0d);
            lookup[value] = (byte) Math.min(255, Math.max(0, corrected));
        }
        return lookup;
    }

    private static void release(Mat... mats) {
        for (Mat mat : mats) {
            if (mat != null) {
                mat.release();
            }
        }
    }
}
