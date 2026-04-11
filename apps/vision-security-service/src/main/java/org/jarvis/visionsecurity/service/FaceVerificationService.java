package org.jarvis.visionsecurity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.Collections;
import java.util.List;

@Slf4j
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

        ThresholdCalibration calibration = calibrateThresholds(normalizedSamples);
        log.info("Enrollment threshold calibration for {}: holdout distances={}, " +
                        "p90={}, max={}, ownerThreshold={}, uncertainThreshold={}",
                userId, calibration.holdoutDistances, calibration.p90,
                calibration.max, calibration.ownerThreshold, calibration.uncertainThreshold);

        EnrollmentProfile profile = enrollmentStore.saveEnrollment(
                userId,
                normalizedSamples,
                calibration.ownerThreshold,
                calibration.uncertainThreshold
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

        try {
            List<FaceMatch> matches = new ArrayList<>();
            for (int index = 0; index < normalizedFaces.size(); index++) {
                Mat face = normalizedFaces.get(index);
                PerSampleResult perSample = computePerSampleDistances(face, samples);
                FaceVerdict verdict = verdictFor(perSample.minDistance, profile);

                log.debug("Face #{}: distances={}, min={}, median={}, verdict={}, " +
                                "ownerThreshold={}, uncertainThreshold={}",
                        index, perSample.distances, perSample.minDistance,
                        perSample.medianDistance, verdict,
                        profile.ownerThreshold(), profile.uncertainThreshold());

                matches.add(new FaceMatch(boxes.get(index), verdict, perSample.minDistance));
            }
            return matches;
        } finally {
            samples.forEach(Mat::release);
        }
    }

    private PerSampleResult computePerSampleDistances(Mat queryFace, List<Mat> enrollmentSamples) {
        List<Double> distances = new ArrayList<>();
        for (int i = 0; i < enrollmentSamples.size(); i++) {
            List<Mat> single = List.of(enrollmentSamples.get(i));
            LBPHFaceRecognizer recognizer = LBPHFaceRecognizer.create(1, 8, 8, 8, Double.MAX_VALUE);
            Mat labels = labels(1);
            try {
                recognizer.train(single, labels);
                int[] predictedLabel = new int[1];
                double[] confidence = new double[1];
                recognizer.predict(queryFace, predictedLabel, confidence);
                distances.add(confidence[0]);
            } finally {
                labels.release();
            }
        }

        List<Double> sorted = new ArrayList<>(distances);
        Collections.sort(sorted);
        double min = sorted.getFirst();
        double median = sorted.get(sorted.size() / 2);

        return new PerSampleResult(distances, min, median);
    }

    private List<FaceMatch> buildUncertainMatches(List<RectBox> boxes) {
        List<FaceMatch> matches = new ArrayList<>();
        for (RectBox box : boxes) {
            matches.add(new FaceMatch(box, FaceVerdict.UNCERTAIN, -1.0));
        }
        return matches;
    }

    private FaceVerdict verdictFor(double bestDistance, EnrollmentProfile profile) {
        if (bestDistance <= profile.ownerThreshold()) {
            return FaceVerdict.OWNER;
        }
        if (bestDistance <= profile.uncertainThreshold()) {
            return FaceVerdict.UNCERTAIN;
        }
        return FaceVerdict.UNKNOWN;
    }

    private ThresholdCalibration calibrateThresholds(List<Mat> normalizedSamples) {
        if (normalizedSamples.size() < 2) {
            double fallback = properties.getVerification().getFallbackOwnerThreshold();
            double fallbackUncertain = properties.getVerification().getFallbackUncertainThreshold();
            return new ThresholdCalibration(List.of(), fallback, fallback, fallback, fallbackUncertain);
        }

        List<Double> holdoutDistances = new ArrayList<>();
        for (int holdout = 0; holdout < normalizedSamples.size(); holdout++) {
            List<Mat> training = new ArrayList<>();
            for (int i = 0; i < normalizedSamples.size(); i++) {
                if (i != holdout) {
                    training.add(normalizedSamples.get(i));
                }
            }

            double minDist = Double.MAX_VALUE;
            for (Mat trainSample : training) {
                LBPHFaceRecognizer recognizer = LBPHFaceRecognizer.create(1, 8, 8, 8, Double.MAX_VALUE);
                Mat labels = labels(1);
                try {
                    recognizer.train(List.of(trainSample), labels);
                    int[] predictedLabel = new int[1];
                    double[] confidence = new double[1];
                    recognizer.predict(normalizedSamples.get(holdout), predictedLabel, confidence);
                    minDist = Math.min(minDist, confidence[0]);
                } finally {
                    labels.release();
                }
            }
            holdoutDistances.add(minDist);
        }

        List<Double> sorted = new ArrayList<>(holdoutDistances);
        Collections.sort(sorted);
        double maxDist = sorted.getLast();
        int p90Index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.9) - 1);
        double p90 = sorted.get(Math.max(0, p90Index));

        double baseline = Math.max(p90, maxDist * 0.85);
        double ownerThreshold = baseline + properties.getVerification().getOwnerThresholdMargin();
        double uncertainThreshold = ownerThreshold + properties.getVerification().getUncertainThresholdMargin();

        return new ThresholdCalibration(holdoutDistances, p90, maxDist, ownerThreshold, uncertainThreshold);
    }

    private Mat labels(int count) {
        Mat labels = new Mat(count, 1, CvType.CV_32SC1);
        for (int index = 0; index < count; index++) {
            labels.put(index, 0, OWNER_LABEL);
        }
        return labels;
    }

    private record PerSampleResult(List<Double> distances, double minDistance, double medianDistance) {
    }

    private record ThresholdCalibration(
            List<Double> holdoutDistances,
            double p90, double max,
            double ownerThreshold, double uncertainThreshold
    ) {
    }
}
