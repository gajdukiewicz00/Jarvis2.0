package org.jarvis.visionsecurity.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.EnrollmentProfile;
import org.jarvis.visionsecurity.model.EnrollmentResult;
import org.jarvis.visionsecurity.model.FaceMatch;
import org.jarvis.visionsecurity.model.FaceVerdict;
import org.jarvis.visionsecurity.model.RectBox;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.face.LBPHFaceRecognizer;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FaceVerificationService {

    private static final int OWNER_LABEL = 1;

    private final EnrollmentStore enrollmentStore;
    private final VisionSecurityProperties properties;

    public boolean isEnrolled(String userId) {
        return enrollmentStore.isEnrolled(userId);
    }

    public EnrollmentProfile loadProfile(String userId) throws IOException {
        return enrollmentStore.loadProfile(userId);
    }

    public EnrollmentResult storeEnrollment(String userId, List<Mat> normalizedSamples) throws IOException {
        if (normalizedSamples == null || normalizedSamples.size() < 3) {
            throw new IllegalArgumentException("At least 3 enrollment samples are required");
        }

        double ownerThreshold = calculateOwnerThreshold(normalizedSamples);
        double uncertainThreshold = ownerThreshold + properties.getVerification().getUncertainThresholdMargin();
        EnrollmentProfile profile = enrollmentStore.saveEnrollment(
                userId,
                normalizedSamples,
                ownerThreshold,
                uncertainThreshold
        );
        return new EnrollmentResult(
                userId,
                profile.enrolledAt(),
                profile.sampleCount(),
                profile.ownerThreshold(),
                profile.uncertainThreshold(),
                profile.sampleDirectory()
        );
    }

    public void resetEnrollment(String userId) throws IOException {
        enrollmentStore.reset(userId);
    }

    public List<FaceMatch> classifyFaces(String userId, List<RectBox> boxes, List<Mat> normalizedFaces) throws IOException {
        EnrollmentProfile profile = enrollmentStore.loadProfile(userId);
        if (profile == null) {
            return buildUncertainMatches(boxes);
        }

        List<Mat> samples = enrollmentStore.loadSamples(userId);
        if (samples.isEmpty()) {
            return buildUncertainMatches(boxes);
        }

        LBPHFaceRecognizer recognizer = LBPHFaceRecognizer.create();
        Mat labels = labels(samples.size());
        try {
            recognizer.train(samples, labels);
            List<FaceMatch> matches = new ArrayList<>();
            for (int index = 0; index < normalizedFaces.size(); index++) {
                Mat face = normalizedFaces.get(index);
                int[] label = new int[1];
                double[] confidence = new double[1];
                recognizer.predict(face, label, confidence);
                matches.add(new FaceMatch(
                        boxes.get(index),
                        verdictFor(label[0], confidence[0], profile),
                        confidence[0]
                ));
            }
            return matches;
        } finally {
            labels.release();
            samples.forEach(Mat::release);
        }
    }

    private List<FaceMatch> buildUncertainMatches(List<RectBox> boxes) {
        List<FaceMatch> matches = new ArrayList<>();
        for (RectBox box : boxes) {
            matches.add(new FaceMatch(box, FaceVerdict.UNCERTAIN, -1.0));
        }
        return matches;
    }

    private FaceVerdict verdictFor(int predictedLabel, double confidence, EnrollmentProfile profile) {
        if (predictedLabel == OWNER_LABEL && confidence <= profile.ownerThreshold()) {
            return FaceVerdict.OWNER;
        }
        if (confidence <= profile.uncertainThreshold()) {
            return FaceVerdict.UNCERTAIN;
        }
        return FaceVerdict.UNKNOWN;
    }

    private double calculateOwnerThreshold(List<Mat> normalizedSamples) {
        if (normalizedSamples.size() < 2) {
            return properties.getVerification().getFallbackOwnerThreshold();
        }

        List<Double> holdoutConfidences = new ArrayList<>();
        for (int index = 0; index < normalizedSamples.size(); index++) {
            List<Mat> training = new ArrayList<>();
            for (int sampleIndex = 0; sampleIndex < normalizedSamples.size(); sampleIndex++) {
                if (sampleIndex != index) {
                    training.add(normalizedSamples.get(sampleIndex));
                }
            }

            LBPHFaceRecognizer recognizer = LBPHFaceRecognizer.create();
            Mat labels = labels(training.size());
            try {
                recognizer.train(training, labels);
                int[] predictedLabel = new int[1];
                double[] confidence = new double[1];
                recognizer.predict(normalizedSamples.get(index), predictedLabel, confidence);
                holdoutConfidences.add(confidence[0]);
            } finally {
                labels.release();
            }
        }

        double baseline = holdoutConfidences.stream()
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(properties.getVerification().getFallbackOwnerThreshold());
        return baseline + properties.getVerification().getOwnerThresholdMargin();
    }

    private Mat labels(int count) {
        Mat labels = new Mat(count, 1, CvType.CV_32SC1);
        for (int index = 0; index < count; index++) {
            labels.put(index, 0, OWNER_LABEL);
        }
        return labels;
    }
}
