package org.jarvis.visionsecurity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.EnrollmentResult;
import org.jarvis.visionsecurity.model.FaceMatch;
import org.jarvis.visionsecurity.model.FaceVerdict;
import org.jarvis.visionsecurity.model.PipelineResult;
import org.jarvis.visionsecurity.model.RectBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VisionSecurityDatasetDiagnosticsTest {

    private static final String USER_ID = "dataset-owner";
    private static final RectBox DUMMY_BOX = new RectBox(0, 0, 160, 160);

    @TempDir
    Path tempDir;

    @Test
    void printsCurrentDatasetMetrics() throws Exception {
        Path datasetRoot = resolveDatasetRoot();
        assertThat(datasetRoot).isDirectory();

        VisionSecurityProperties properties = new VisionSecurityProperties();
        properties.getStorage().setRoot(tempDir.toString());

        EnrollmentStore enrollmentStore = new EnrollmentStore(properties, new ObjectMapper().findAndRegisterModules());
        FaceVerificationService faceVerificationService = new FaceVerificationService(enrollmentStore, properties);
        VisionPipelineService pipelineService = new VisionPipelineService(
                properties,
                new OpenCvRuntime(),
                faceVerificationService
        );

        List<Path> enrollmentPaths = imagePaths(datasetRoot.resolve("owner/enrollment"));
        List<Path> validationPaths = imagePaths(datasetRoot.resolve("owner/validation"));
        List<Path> noFacePaths = imagePaths(datasetRoot.resolve("negative/no_face"));
        List<Path> multiFacePaths = imagePaths(datasetRoot.resolve("negative/multiple_faces"));

        List<Mat> enrollmentSamples = new ArrayList<>();
        Map<String, String> enrollmentFailures = new LinkedHashMap<>();
        try {
            for (Path imagePath : enrollmentPaths) {
                try {
                    enrollmentSamples.add(extractNormalizedFace(pipelineService, imagePath));
                } catch (IllegalStateException ex) {
                    enrollmentFailures.put(imagePath.getFileName().toString(), ex.getMessage());
                }
            }
            assertThat(enrollmentSamples.size()).isGreaterThanOrEqualTo(3);

            EnrollmentResult enrollment = faceVerificationService.storeEnrollment(USER_ID, enrollmentSamples);

            ScoringSummary enrollmentScoring = scoreSingleFaceImages(faceVerificationService, pipelineService, enrollmentPaths);
            ScoringSummary validationScoring = scoreSingleFaceImages(faceVerificationService, pipelineService, validationPaths);
            DetectionSummary enrollmentDetectionSummary = detectionSummary(pipelineService, enrollmentPaths);
            DetectionSummary noFaceSummary = detectionSummary(pipelineService, noFacePaths);
            DetectionSummary multiFaceSummary = detectionSummary(pipelineService, multiFacePaths);
            MultiFaceOwnerSummary multiFaceOwnerSummary = multiFaceOwnerSummary(pipelineService, multiFacePaths);

            System.out.println();
            System.out.println("=== Vision Security Dataset Baseline ===");
            System.out.println("ownerThreshold=" + enrollment.ownerThreshold());
            System.out.println("uncertainThreshold=" + enrollment.uncertainThreshold());
            System.out.println("enrollment detection=" + enrollmentDetectionSummary);
            System.out.println("enrollment extraction failures=" + enrollmentFailures);
            System.out.println("enrollment distances=" + summarize(enrollmentScoring.distances()));
            System.out.println("enrollment scoring failures=" + enrollmentScoring.failures());
            System.out.println("validation distances=" + summarize(validationScoring.distances()));
            System.out.println("validation scoring failures=" + validationScoring.failures());
            System.out.println("no-face detection=" + noFaceSummary);
            System.out.println("multi-face detection=" + multiFaceSummary);
            System.out.println("multi-face owner assignments=" + multiFaceOwnerSummary);
            System.out.println("validation accept rate="
                    + validationScoring.distances().stream().filter(d -> d <= enrollment.ownerThreshold()).count()
                    + "/" + validationScoring.distances().size());

            assertThat(enrollmentDetectionSummary.zeroFaceFrames()).isZero();
            assertThat(enrollmentDetectionSummary.multiFaceFrames()).isZero();
            assertThat(enrollmentFailures).isEmpty();
            assertThat(enrollmentScoring.failures()).isEmpty();
            assertThat(validationScoring.failures()).isEmpty();
            assertThat(validationScoring.distances()).hasSize(validationPaths.size());
            assertThat(validationScoring.distances()).allMatch(distance -> distance <= enrollment.ownerThreshold());
            assertThat(noFaceSummary.zeroFaceFrames()).isEqualTo(noFacePaths.size());
            assertThat(noFaceSummary.multiFaceFrames()).isZero();
        } finally {
            enrollmentSamples.forEach(Mat::release);
        }
    }

    private Path resolveDatasetRoot() {
        Path[] candidates = new Path[]{
                Path.of("dataset"),
                Path.of("apps/vision-security-service/dataset")
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return candidates[0];
    }

    private ScoringSummary scoreSingleFaceImages(
            FaceVerificationService faceVerificationService,
            VisionPipelineService pipelineService,
            List<Path> paths
    ) throws Exception {
        List<Double> distances = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        for (Path path : paths) {
            Mat normalizedFace;
            try {
                normalizedFace = extractNormalizedFace(pipelineService, path);
            } catch (IllegalStateException ex) {
                failures.put(path.getFileName().toString(), ex.getMessage());
                continue;
            }
            try {
                FaceMatch match = faceVerificationService.classifyFaces(
                        USER_ID,
                        List.of(DUMMY_BOX),
                        List.of(normalizedFace)
                ).getFirst();
                distances.add(match.confidence());
            } finally {
                normalizedFace.release();
            }
        }
        return new ScoringSummary(distances, failures);
    }

    private DetectionSummary detectionSummary(VisionPipelineService pipelineService, List<Path> paths) throws Exception {
        List<Integer> faceCounts = new ArrayList<>();
        for (Path path : paths) {
            Mat frame = Imgcodecs.imread(path.toString());
            try {
                PipelineResult result = pipelineService.analyze(USER_ID, frame, null);
                faceCounts.add(result.faceCount());
            } finally {
                frame.release();
            }
        }

        long zeroFaceFrames = faceCounts.stream().filter(count -> count == 0).count();
        long multiFaceFrames = faceCounts.stream().filter(count -> count > 1).count();
        return new DetectionSummary(paths.size(), zeroFaceFrames, multiFaceFrames, faceCounts);
    }

    private MultiFaceOwnerSummary multiFaceOwnerSummary(VisionPipelineService pipelineService, List<Path> paths) throws Exception {
        List<Integer> ownerAssignments = new ArrayList<>();
        for (Path path : paths) {
            Mat frame = Imgcodecs.imread(path.toString());
            try {
                PipelineResult result = pipelineService.analyze(USER_ID, frame, null);
                int ownerCount = (int) result.faces().stream()
                        .filter(match -> match.verdict() == FaceVerdict.OWNER)
                        .count();
                ownerAssignments.add(ownerCount);
            } finally {
                frame.release();
            }
        }
        return new MultiFaceOwnerSummary(ownerAssignments);
    }

    private Mat extractNormalizedFace(VisionPipelineService pipelineService, Path imagePath) {
        Mat frame = Imgcodecs.imread(imagePath.toString());
        try {
            return pipelineService.extractEnrollmentFace(frame);
        } finally {
            frame.release();
        }
    }

    private List<Path> imagePaths(Path directory) throws Exception {
        assertThat(directory).isDirectory();
        try (var stream = Files.list(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        }
    }

    private String summarize(List<Double> values) {
        DoubleSummaryStatistics stats = values.stream()
                .mapToDouble(Double::doubleValue)
                .summaryStatistics();
        return "count=" + stats.getCount()
                + ", min=" + String.format("%.2f", stats.getMin())
                + ", avg=" + String.format("%.2f", stats.getAverage())
                + ", max=" + String.format("%.2f", stats.getMax())
                + ", raw=" + values;
    }

    private record DetectionSummary(
            int totalFrames,
            long zeroFaceFrames,
            long multiFaceFrames,
            List<Integer> faceCounts
    ) {
        @Override
        public String toString() {
            return "total=" + totalFrames
                    + ", zeroFaceFrames=" + zeroFaceFrames
                    + ", multiFaceFrames=" + multiFaceFrames
                    + ", counts=" + faceCounts;
        }
    }

    private record ScoringSummary(
            List<Double> distances,
            Map<String, String> failures
    ) {
    }

    private record MultiFaceOwnerSummary(List<Integer> ownerAssignmentsPerFrame) {
        @Override
        public String toString() {
            return ownerAssignmentsPerFrame.toString();
        }
    }
}
