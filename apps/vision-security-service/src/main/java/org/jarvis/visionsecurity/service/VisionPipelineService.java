package org.jarvis.visionsecurity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.DecisionType;
import org.jarvis.visionsecurity.model.FaceMatch;
import org.jarvis.visionsecurity.model.FaceVerdict;
import org.jarvis.visionsecurity.model.PipelineResult;
import org.jarvis.visionsecurity.model.RectBox;
import org.jarvis.visionsecurity.model.StagePaths;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfRect;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisionPipelineService {

    private final VisionSecurityProperties properties;
    private final OpenCvRuntime openCvRuntime;
    private final FaceVerificationService faceVerificationService;

    public PipelineResult analyze(String userId, Mat frame, Path outputDirectory) throws IOException {
        if (frame == null || frame.empty()) {
            throw new IllegalArgumentException("Frame is empty");
        }

        Mat enhancedColor = new Mat();
        Mat enhancedGray = new Mat();
        Mat rawGray = new Mat();
        Mat segmentationMask = new Mat();
        Mat cleanedMask = new Mat();
        Mat detectionImage = new Mat();
        Mat decisionOverlay = new Mat();
        Mat contoursMask = new Mat();
        StagePaths stagePaths = null;
        String rawFramePath = null;
        List<Mat> normalizedFaces = new ArrayList<>();

        try {
            enhance(frame, enhancedColor, enhancedGray);
            Imgproc.cvtColor(frame, rawGray, Imgproc.COLOR_BGR2GRAY);
            segment(enhancedColor, segmentationMask, cleanedMask);
            List<Rect> detectedFaces = detectFaces(frame, rawGray);
            List<RectBox> boxes = toBoxes(detectedFaces);

            frame.copyTo(detectionImage);
            contoursMask = cleanedMask.clone();
            drawDetectionContours(detectionImage, contoursMask);
            drawFaceBoxes(detectionImage, detectedFaces, new Scalar(80, 180, 255), "face");

            for (Rect rect : detectedFaces) {
                normalizedFaces.add(normalizeFace(rawGray, rect));
            }

            List<FaceMatch> matches = faceVerificationService.classifyFaces(userId, boxes, normalizedFaces);
            DecisionType decision = aggregateDecision(userId, matches, detectedFaces.isEmpty());
            String reason = reasonFor(userId, matches, detectedFaces.isEmpty());

            frame.copyTo(decisionOverlay);
            drawDecisionOverlay(decisionOverlay, matches, decision);

            if (outputDirectory != null) {
                Files.createDirectories(outputDirectory);
                rawFramePath = save(frame, outputDirectory.resolve("original.png"));
                stagePaths = new StagePaths(
                        rawFramePath,
                        save(enhancedColor, outputDirectory.resolve("enhanced.png")),
                        save(segmentationMask, outputDirectory.resolve("segmentation-mask.png")),
                        save(cleanedMask, outputDirectory.resolve("cleaned-mask.png")),
                        save(detectionImage, outputDirectory.resolve("detection-result.png")),
                        save(decisionOverlay, outputDirectory.resolve("final-decision.png"))
                );
            }

            return new PipelineResult(
                    decision,
                    matches.size(),
                    reason,
                    matches,
                    stagePaths,
                    rawFramePath
            );
        } finally {
            enhancedColor.release();
            enhancedGray.release();
            rawGray.release();
            segmentationMask.release();
            cleanedMask.release();
            detectionImage.release();
            decisionOverlay.release();
            contoursMask.release();
            normalizedFaces.forEach(Mat::release);
        }
    }

    public Mat extractEnrollmentFace(Mat frame) {
        Mat enhancedColor = new Mat();
        Mat enhancedGray = new Mat();
        Mat rawGray = new Mat();
        try {
            enhance(frame, enhancedColor, enhancedGray);
            Imgproc.cvtColor(frame, rawGray, Imgproc.COLOR_BGR2GRAY);
            List<Rect> faces = detectFaces(frame, rawGray);
            if (faces.isEmpty()) {
                throw new IllegalStateException("No face detected");
            }
            if (faces.size() > 1) {
                throw new IllegalStateException("Multiple faces detected");
            }
            return normalizeFace(rawGray, faces.getFirst());
        } finally {
            enhancedColor.release();
            enhancedGray.release();
            rawGray.release();
        }
    }

    public double measureFaceSharpness(Mat normalizedFace) {
        Mat laplacian = new Mat();
        org.opencv.core.MatOfDouble mean = new org.opencv.core.MatOfDouble();
        org.opencv.core.MatOfDouble stdDev = new org.opencv.core.MatOfDouble();
        try {
            Imgproc.Laplacian(normalizedFace, laplacian, CvType.CV_64F);
            Core.meanStdDev(laplacian, mean, stdDev);
            double variance = stdDev.get(0, 0)[0];
            return variance * variance;
        } finally {
            laplacian.release();
            mean.release();
            stdDev.release();
        }
    }

    public double measureFaceContrast(Mat normalizedFace) {
        org.opencv.core.MatOfDouble mean = new org.opencv.core.MatOfDouble();
        org.opencv.core.MatOfDouble stdDev = new org.opencv.core.MatOfDouble();
        try {
            Core.meanStdDev(normalizedFace, mean, stdDev);
            return stdDev.get(0, 0)[0];
        } finally {
            mean.release();
            stdDev.release();
        }
    }

    public String computeDifferenceHash(Mat normalizedFace) {
        Mat resized = new Mat();
        try {
            Imgproc.resize(normalizedFace, resized, new Size(9, 8));
            StringBuilder bits = new StringBuilder(64);
            for (int row = 0; row < resized.rows(); row++) {
                for (int col = 0; col < resized.cols() - 1; col++) {
                    double left = resized.get(row, col)[0];
                    double right = resized.get(row, col + 1)[0];
                    bits.append(right > left ? '1' : '0');
                }
            }
            return bits.toString();
        } finally {
            resized.release();
        }
    }

    public int hammingDistance(String left, String right) {
        if (left == null || right == null || left.length() != right.length()) {
            return Integer.MAX_VALUE;
        }
        int distance = 0;
        for (int index = 0; index < left.length(); index++) {
            if (left.charAt(index) != right.charAt(index)) {
                distance++;
            }
        }
        return distance;
    }

    private void enhance(Mat source, Mat enhancedColor, Mat enhancedGray) {
        Mat gammaCorrected = new Mat();
        Mat lookup = buildGammaLookupTable(1.3);
        try {
            Core.LUT(source, lookup, gammaCorrected);
            gammaCorrected.copyTo(enhancedColor);
            Imgproc.cvtColor(gammaCorrected, enhancedGray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.equalizeHist(enhancedGray, enhancedGray);
            Imgproc.GaussianBlur(enhancedGray, enhancedGray, new Size(3, 3), 0);
            Imgproc.cvtColor(enhancedGray, enhancedColor, Imgproc.COLOR_GRAY2BGR);
        } finally {
            gammaCorrected.release();
            lookup.release();
        }
    }

    private void segment(Mat enhancedColor, Mat segmentationMask, Mat cleanedMask) {
        Mat yCrCb = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
        try {
            Imgproc.cvtColor(enhancedColor, yCrCb, Imgproc.COLOR_BGR2YCrCb);
            Core.inRange(yCrCb, new Scalar(0, 133, 77), new Scalar(255, 173, 127), segmentationMask);
            Imgproc.morphologyEx(segmentationMask, cleanedMask, Imgproc.MORPH_OPEN, kernel);
            Imgproc.morphologyEx(cleanedMask, cleanedMask, Imgproc.MORPH_CLOSE, kernel);
        } finally {
            yCrCb.release();
            kernel.release();
        }
    }

    private List<Rect> detectFaces(Mat frame, Mat gray) {
        double scaleFactor = properties.getVerification().getDetectionScaleFactor();
        int minNeighbors = properties.getVerification().getDetectionMinNeighbors();
        double minArea = frame.width() * frame.height() * properties.getVerification().getMinDetectionAreaRatio();
        Size minSize = new Size(50, 50);

        Set<String> seen = new LinkedHashSet<>();
        List<Rect> combined = new ArrayList<>();

        Path altCascadePath = openCvRuntime.getAltFaceCascadePath();
        if (altCascadePath != null) {
            List<Rect> altResults = runCascadeDetection(altCascadePath, gray, scaleFactor, minNeighbors, minSize);
            for (Rect rect : altResults) {
                String key = rect.x + "," + rect.y + "," + rect.width + "," + rect.height;
                if (seen.add(key)) {
                    combined.add(rect);
                }
            }
        }

        List<Rect> defaultResults = runCascadeDetection(
                openCvRuntime.getFaceCascadePath(), gray, scaleFactor, minNeighbors, minSize);
        for (Rect rect : defaultResults) {
            boolean overlaps = combined.stream().anyMatch(existing -> computeIoU(existing, rect) > 0.4);
            if (!overlaps) {
                combined.add(rect);
            }
        }

        List<Rect> filtered = combined.stream()
                .filter(rect -> (double) rect.width * rect.height >= minArea)
                .sorted(Comparator.comparingInt((Rect rect) -> rect.width * rect.height).reversed())
                .toList();

        log.debug("Face detection: {} from alt cascade, {} from default, {} after merge/filter",
                altCascadePath != null ? combined.size() : 0, defaultResults.size(), filtered.size());

        return filtered;
    }

    private List<Rect> runCascadeDetection(Path cascadePath, Mat gray, double scaleFactor, int minNeighbors, Size minSize) {
        CascadeClassifier classifier = new CascadeClassifier(cascadePath.toString());
        if (classifier.empty()) {
            log.warn("Failed to load cascade: {}", cascadePath);
            return List.of();
        }

        MatOfRect faces = new MatOfRect();
        try {
            classifier.detectMultiScale(gray, faces, scaleFactor, minNeighbors, 0, minSize, new Size());
            return Arrays.asList(faces.toArray());
        } finally {
            faces.release();
        }
    }

    private double computeIoU(Rect a, Rect b) {
        int x1 = Math.max(a.x, b.x);
        int y1 = Math.max(a.y, b.y);
        int x2 = Math.min(a.x + a.width, b.x + b.width);
        int y2 = Math.min(a.y + a.height, b.y + b.height);
        if (x2 <= x1 || y2 <= y1) {
            return 0.0;
        }
        double intersection = (double) (x2 - x1) * (y2 - y1);
        double areaA = (double) a.width * a.height;
        double areaB = (double) b.width * b.height;
        return intersection / (areaA + areaB - intersection);
    }

    private Mat normalizeFace(Mat rawGray, Rect faceRect) {
        VisionSecurityProperties.Verification verif = properties.getVerification();
        double padRatio = verif.getFacePaddingRatio();
        int padX = (int) (faceRect.width * padRatio);
        int padY = (int) (faceRect.height * padRatio);

        int cropX = Math.max(0, faceRect.x - padX);
        int cropY = Math.max(0, faceRect.y - padY);
        int cropW = Math.min(rawGray.cols() - cropX, faceRect.width + 2 * padX);
        int cropH = Math.min(rawGray.rows() - cropY, faceRect.height + 2 * padY);
        Rect paddedRect = new Rect(cropX, cropY, cropW, cropH);

        Mat faceCrop = new Mat(rawGray, paddedRect);
        Mat claheResult = new Mat();
        Mat aligned = null;
        Mat resized = new Mat();

        try {
            CLAHE clahe = Imgproc.createCLAHE(verif.getClaheClipLimit(),
                    new Size(verif.getClaheGridSize(), verif.getClaheGridSize()));
            clahe.apply(faceCrop, claheResult);

            if (verif.isEnableEyeAlignment() && openCvRuntime.getEyeCascadePath() != null) {
                aligned = attemptEyeAlignment(claheResult);
            }

            Mat source = aligned != null ? aligned : claheResult;
            int targetSize = verif.getNormalizedFaceSize();
            Imgproc.resize(source, resized, new Size(targetSize, targetSize));
            return resized.clone();
        } finally {
            faceCrop.release();
            claheResult.release();
            if (aligned != null) {
                aligned.release();
            }
            resized.release();
        }
    }

    private Mat attemptEyeAlignment(Mat faceCrop) {
        Path eyeCascadePath = openCvRuntime.getEyeCascadePath();
        if (eyeCascadePath == null) {
            return null;
        }

        CascadeClassifier eyeDetector = new CascadeClassifier(eyeCascadePath.toString());
        if (eyeDetector.empty()) {
            return null;
        }

        MatOfRect eyes = new MatOfRect();
        try {
            int faceW = faceCrop.cols();
            int faceH = faceCrop.rows();
            Size minEye = new Size(faceW * 0.12, faceH * 0.12);
            Size maxEye = new Size(faceW * 0.40, faceH * 0.40);
            eyeDetector.detectMultiScale(faceCrop, eyes, 1.1, 4, 0, minEye, maxEye);

            Rect[] eyeArray = eyes.toArray();
            if (eyeArray.length < 2) {
                return null;
            }

            List<Rect> topEyes = Arrays.stream(eyeArray)
                    .filter(eye -> eye.y + eye.height / 2.0 < faceH * 0.55)
                    .sorted(Comparator.comparingInt(eye -> eye.x))
                    .toList();

            if (topEyes.size() < 2) {
                return null;
            }

            Rect leftEye = topEyes.getFirst();
            Rect rightEye = topEyes.get(topEyes.size() - 1);

            double leftCenterX = leftEye.x + leftEye.width / 2.0;
            double leftCenterY = leftEye.y + leftEye.height / 2.0;
            double rightCenterX = rightEye.x + rightEye.width / 2.0;
            double rightCenterY = rightEye.y + rightEye.height / 2.0;

            double angle = Math.toDegrees(Math.atan2(rightCenterY - leftCenterY, rightCenterX - leftCenterX));

            if (Math.abs(angle) < 0.5 || Math.abs(angle) > 25.0) {
                return null;
            }

            Point center = new Point(faceW / 2.0, faceH / 2.0);
            Mat rotationMatrix = Imgproc.getRotationMatrix2D(center, angle, 1.0);
            Mat rotated = new Mat();
            try {
                Imgproc.warpAffine(faceCrop, rotated, rotationMatrix, faceCrop.size(),
                        Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE, new Scalar(0));
                log.debug("Eye alignment applied: angle={}°", String.format("%.1f", angle));
                return rotated.clone();
            } finally {
                rotationMatrix.release();
                rotated.release();
            }
        } finally {
            eyes.release();
        }
    }

    private void drawFaceBoxes(Mat target, List<Rect> faces, Scalar color, String prefix) {
        int index = 1;
        for (Rect rect : faces) {
            Imgproc.rectangle(target, rect.tl(), rect.br(), color, 2);
            Imgproc.putText(target, prefix + "-" + index++,
                    rect.tl(), Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, color, 1);
        }
    }

    private void drawDetectionContours(Mat target, Mat mask) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        try {
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            for (MatOfPoint contour : contours) {
                try {
                    Rect boundingRect = Imgproc.boundingRect(contour);
                    if (boundingRect.width * boundingRect.height < 4_000) {
                        continue;
                    }
                    Imgproc.rectangle(target, boundingRect.tl(), boundingRect.br(), new Scalar(255, 200, 0), 1);
                } finally {
                    contour.release();
                }
            }
        } finally {
            hierarchy.release();
        }
    }

    private void drawDecisionOverlay(Mat target, List<FaceMatch> matches, DecisionType decision) {
        for (FaceMatch match : matches) {
            Scalar color = switch (match.verdict()) {
                case OWNER -> new Scalar(0, 220, 0);
                case UNKNOWN -> new Scalar(0, 0, 255);
                case UNCERTAIN -> new Scalar(0, 255, 255);
            };
            RectBox box = match.box();
            Rect rect = new Rect(box.x(), box.y(), box.width(), box.height());
            Imgproc.rectangle(target, rect.tl(), rect.br(), color, 2);
            String label = match.verdict() + " " + String.format("%.1f", match.confidence());
            Imgproc.putText(target, label, rect.tl(), Imgproc.FONT_HERSHEY_SIMPLEX, 0.55, color, 2);
        }

        Imgproc.putText(
                target,
                "Decision: " + decision,
                new Point(20, 28),
                Imgproc.FONT_HERSHEY_SIMPLEX,
                0.8,
                new Scalar(255, 255, 255),
                2
        );
    }

    private DecisionType aggregateDecision(String userId, List<FaceMatch> matches, boolean noFacesDetected) throws IOException {
        if (noFacesDetected || matches.isEmpty()) {
            return DecisionType.NO_FACE;
        }
        if (!faceVerificationService.isEnrolled(userId)) {
            return DecisionType.UNCERTAIN;
        }
        if (matches.stream().anyMatch(match -> match.verdict() == FaceVerdict.OWNER)) {
            return DecisionType.OWNER_PRESENT;
        }
        if (matches.stream().anyMatch(match -> match.verdict() == FaceVerdict.UNCERTAIN)) {
            return DecisionType.UNCERTAIN;
        }
        return DecisionType.UNKNOWN_PERSON;
    }

    private String reasonFor(String userId, List<FaceMatch> matches, boolean noFacesDetected) throws IOException {
        if (noFacesDetected || matches.isEmpty()) {
            return "No face detected in the current frame";
        }
        if (!faceVerificationService.isEnrolled(userId)) {
            return "Owner enrollment is missing, so verification stays uncertain";
        }
        if (matches.stream().anyMatch(match -> match.verdict() == FaceVerdict.OWNER)) {
            return "At least one detected face matched the enrolled owner profile";
        }
        if (matches.stream().anyMatch(match -> match.verdict() == FaceVerdict.UNCERTAIN)) {
            return "Faces were detected but verification confidence stayed between owner and unknown thresholds";
        }
        return "Detected faces stayed outside the owner threshold";
    }

    private List<RectBox> toBoxes(List<Rect> faces) {
        return faces.stream()
                .map(rect -> new RectBox(rect.x, rect.y, rect.width, rect.height))
                .toList();
    }

    private String save(Mat mat, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        if (!Imgcodecs.imwrite(path.toString(), mat)) {
            throw new IOException("Failed to write image to " + path);
        }
        return path.toString();
    }

    private Mat buildGammaLookupTable(double gamma) {
        Mat lookup = new Mat(1, 256, CvType.CV_8U);
        for (int value = 0; value < 256; value++) {
            double corrected = Math.pow(value / 255.0, 1.0 / gamma) * 255.0;
            lookup.put(0, value, Math.min(255, Math.max(0, corrected)));
        }
        return lookup;
    }
}
