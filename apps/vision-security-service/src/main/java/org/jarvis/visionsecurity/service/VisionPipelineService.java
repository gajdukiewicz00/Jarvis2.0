package org.jarvis.visionsecurity.service;

import lombok.RequiredArgsConstructor;
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
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

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
            segment(enhancedColor, segmentationMask, cleanedMask);
            List<Rect> detectedFaces = detectFaces(frame, enhancedGray);
            List<RectBox> boxes = toBoxes(detectedFaces);

            frame.copyTo(detectionImage);
            contoursMask = cleanedMask.clone();
            drawDetectionContours(detectionImage, contoursMask);
            drawFaceBoxes(detectionImage, detectedFaces, new Scalar(80, 180, 255), "face");

            for (Rect rect : detectedFaces) {
                normalizedFaces.add(normalizeFace(enhancedGray, rect));
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
        try {
            enhance(frame, enhancedColor, enhancedGray);
            List<Rect> faces = detectFaces(frame, enhancedGray);
            if (faces.isEmpty()) {
                throw new IllegalStateException("No face detected");
            }
            if (faces.size() > 1) {
                throw new IllegalStateException("Multiple faces detected");
            }
            return normalizeFace(enhancedGray, faces.getFirst());
        } finally {
            enhancedColor.release();
            enhancedGray.release();
        }
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
        CascadeClassifier classifier = new CascadeClassifier(openCvRuntime.getFaceCascadePath().toString());
        if (classifier.empty()) {
            throw new IllegalStateException("Failed to load face cascade classifier");
        }

        MatOfRect faces = new MatOfRect();
        try {
            classifier.detectMultiScale(
                    gray,
                    faces,
                    1.1,
                    4,
                    0,
                    new Size(80, 80),
                    new Size()
            );
            double minArea = frame.width() * frame.height() * properties.getVerification().getMinDetectionAreaRatio();
            return Arrays.stream(faces.toArray())
                    .filter((Rect rect) -> (double) rect.width * rect.height >= minArea)
                    .sorted(Comparator.comparingInt((Rect rect) -> rect.width * rect.height).reversed())
                    .toList();
        } finally {
            faces.release();
        }
    }

    private Mat normalizeFace(Mat gray, Rect rect) {
        Mat faceRegion = new Mat(gray, rect);
        Mat normalized = new Mat();
        try {
            Imgproc.resize(faceRegion, normalized,
                    new Size(properties.getVerification().getNormalizedFaceSize(),
                            properties.getVerification().getNormalizedFaceSize()));
            return normalized.clone();
        } finally {
            faceRegion.release();
            normalized.release();
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
                new org.opencv.core.Point(20, 28),
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
