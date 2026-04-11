package org.jarvis.visionsecurity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.jarvis.visionsecurity.model.DecisionType;
import org.jarvis.visionsecurity.model.EmailDelivery;
import org.jarvis.visionsecurity.model.EnrollmentResult;
import org.jarvis.visionsecurity.model.GpuStatus;
import org.jarvis.visionsecurity.model.IncidentRecord;
import org.jarvis.visionsecurity.model.PipelineResult;
import org.jarvis.visionsecurity.model.PipelineSnapshotResult;
import org.jarvis.visionsecurity.model.ScreenContextEvidence;
import org.jarvis.visionsecurity.model.StagePaths;
import org.jarvis.visionsecurity.model.VisionSecurityConfigView;
import org.jarvis.visionsecurity.model.VisionSecurityStatus;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisionSecurityManager {

    private static final int MIN_ENROLLMENT_SAMPLES = 5;

    private final VisionSecurityProperties properties;
    private final CameraCaptureService cameraCaptureService;
    private final VisionPipelineService visionPipelineService;
    private final FaceVerificationService faceVerificationService;
    private final IncidentStore incidentStore;
    private final ScreenshotService screenshotService;
    private final ScreenContextService screenContextService;
    private final EmailAlertService emailAlertService;
    private final GpuStatusService gpuStatusService;
    private final OcrService ocrService;

    private final ScheduledExecutorService monitoringExecutor = Executors.newSingleThreadScheduledExecutor(runnable ->
            Thread.ofVirtual().name("jarvis-vision-security-monitor").unstarted(runnable));

    private final AtomicBoolean monitoringEnabled = new AtomicBoolean(false);
    private final AtomicReference<String> activeExclusiveOperation = new AtomicReference<>();
    private volatile ScheduledFuture<?> monitoringTask;
    private volatile String activeUserId;
    private volatile DecisionType lastDecision;
    private volatile Instant lastDecisionAt;
    private volatile String lastReason = "Monitoring is idle";
    private volatile int lastFaceCount;
    private volatile int lastUnknownStreak;
    private volatile String lastIncidentId;
    private volatile CapabilityStatus lastCameraStatus = new CapabilityStatus("UNKNOWN", "Camera not checked yet");
    private volatile MonitoringDecisionEngine decisionEngine;

    @PreDestroy
    public void shutdown() {
        stopMonitoring(null);
        monitoringExecutor.shutdownNow();
    }

    public synchronized VisionSecurityStatus startMonitoring(String userId) {
        if (monitoringEnabled.get()) {
            lastReason = "Monitoring already active";
            return statusFor(userId);
        }

        String exclusiveOperation = activeExclusiveOperation.get();
        if (exclusiveOperation != null) {
            throw new IllegalStateException("Cannot start monitoring while " + exclusiveOperation + " is using the camera");
        }

        monitoringEnabled.set(true);
        activeUserId = userId;
        decisionEngine = new MonitoringDecisionEngine(
                properties.getMonitoring().getDebounceUnknownFrames(),
                Duration.ofSeconds(properties.getMonitoring().getAlertCooldownSeconds()),
                properties.getMonitoring().getOwnerGraceFrames()
        );
        lastCameraStatus = new CapabilityStatus("AVAILABLE", "Camera reserved for monitoring");
        monitoringTask = monitoringExecutor.scheduleWithFixedDelay(
                () -> monitorOnce(userId),
                0,
                properties.getMonitoring().getCheckIntervalMs(),
                TimeUnit.MILLISECONDS
        );
        lastReason = "Monitoring started";
        return statusFor(userId);
    }

    public synchronized VisionSecurityStatus stopMonitoring(String userId) {
        String statusUser = userId == null || userId.isBlank() ? activeUserId : userId;
        if (!monitoringEnabled.get() && monitoringTask == null) {
            lastReason = "Monitoring already stopped";
            return statusFor(statusUser);
        }

        monitoringEnabled.set(false);
        activeUserId = null;
        ScheduledFuture<?> current = monitoringTask;
        monitoringTask = null;
        if (current != null) {
            current.cancel(true);
        }
        if (decisionEngine != null) {
            decisionEngine.reset();
        }
        lastUnknownStreak = 0;
        lastReason = "Monitoring stopped";
        return statusFor(statusUser);
    }

    public EnrollmentResult captureEnrollment(String userId, Integer sampleCount) throws Exception {
        int requestedSamples = sampleCount == null ? properties.getEnrollment().getSampleCount() : sampleCount;
        if (requestedSamples < MIN_ENROLLMENT_SAMPLES) {
            throw new IllegalArgumentException("Enrollment needs at least " + MIN_ENROLLMENT_SAMPLES + " samples");
        }

        return runExclusiveCameraOperation("owner enrollment", () -> {
            Instant deadline = Instant.now().plusSeconds(properties.getEnrollment().getCaptureTimeoutSeconds());
            List<Mat> samples = new java.util.ArrayList<>();
            List<String> sampleHashes = new java.util.ArrayList<>();
            try {
                return cameraCaptureService.withCameraSession("owner enrollment", session -> {
                    int frameCount = 0;
                    java.util.Map<String, Integer> rejections = new java.util.LinkedHashMap<>();
                    while (samples.size() < requestedSamples && Instant.now().isBefore(deadline)) {
                        Mat frame = session.captureFrame();
                        frameCount++;
                        try {
                            Mat sample = visionPipelineService.extractEnrollmentFace(frame);
                            EnrollmentSampleAssessment assessment = assessEnrollmentSample(sample, sampleHashes);

                            if (assessment.rejectionReason() != null) {
                                sample.release();
                                rejections.merge(assessment.rejectionReason(), 1, Integer::sum);
                                continue;
                            }

                            sampleHashes.add(assessment.sampleHash());
                            log.info("Enrollment sample {}/{} accepted: sharpness={}, contrast={}, nearestHashDistance={}",
                                    samples.size() + 1, requestedSamples,
                                    String.format("%.1f", assessment.sharpness()),
                                    String.format("%.1f", assessment.contrast()),
                                    assessment.nearestHashDistance() == Integer.MAX_VALUE ? "n/a" : assessment.nearestHashDistance());
                            samples.add(sample);
                            sleepBetweenEnrollmentSamples();
                        } catch (IllegalStateException ex) {
                            String reason = ex.getMessage() == null ? "unknown" : ex.getMessage();
                            rejections.merge(reason, 1, Integer::sum);
                        } finally {
                            frame.release();
                        }
                    }

                    if (samples.size() < requestedSamples) {
                        StringBuilder sb = new StringBuilder("Enrollment failed: ")
                                .append(samples.size()).append("/").append(requestedSamples)
                                .append(" samples from ").append(frameCount).append(" frames.");
                        if (!rejections.isEmpty()) {
                            sb.append(" Rejections:");
                            rejections.forEach((reason, count) -> sb.append(" [").append(reason).append("]=").append(count));
                        }
                        throw new IllegalStateException(sb.toString());
                    }

                    return faceVerificationService.storeEnrollment(userId, samples);
                });
            } finally {
                samples.forEach(Mat::release);
            }
        });
    }

    public EnrollmentResult importEnrollmentFromDataset(String userId, Path datasetDirectory) throws IOException {
        if (!Files.isDirectory(datasetDirectory)) {
            throw new IllegalArgumentException("Dataset directory does not exist: " + datasetDirectory);
        }

        List<Path> imagePaths;
        try (Stream<Path> stream = Files.list(datasetDirectory)) {
            imagePaths = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
                    })
                    .sorted()
                    .toList();
        }

        if (imagePaths.isEmpty()) {
            throw new IllegalArgumentException("No image files found in " + datasetDirectory);
        }

        List<Mat> acceptedSamples = new java.util.ArrayList<>();
        List<String> acceptedHashes = new java.util.ArrayList<>();
        Map<String, Integer> rejections = new LinkedHashMap<>();
        int processed = 0;

        try {
            for (Path imagePath : imagePaths) {
                processed++;
                Mat frame = Imgcodecs.imread(imagePath.toString());
                if (frame.empty()) {
                    rejections.merge("unreadable image", 1, Integer::sum);
                    frame.release();
                    continue;
                }

                try {
                    Mat sample = visionPipelineService.extractEnrollmentFace(frame);
                    EnrollmentSampleAssessment assessment = assessEnrollmentSample(sample, acceptedHashes);
                    if (assessment.rejectionReason() != null) {
                        sample.release();
                        rejections.merge(assessment.rejectionReason(), 1, Integer::sum);
                        continue;
                    }

                    acceptedHashes.add(assessment.sampleHash());
                    log.info("Dataset import: accepted {} (sharpness={}, contrast={}, nearestHashDistance={})",
                            imagePath.getFileName(),
                            String.format("%.1f", assessment.sharpness()),
                            String.format("%.1f", assessment.contrast()),
                            assessment.nearestHashDistance() == Integer.MAX_VALUE ? "n/a" : assessment.nearestHashDistance());
                    acceptedSamples.add(sample);
                } catch (IllegalStateException ex) {
                    rejections.merge(ex.getMessage() != null ? ex.getMessage() : "unknown", 1, Integer::sum);
                } finally {
                    frame.release();
                }
            }

            if (acceptedSamples.size() < MIN_ENROLLMENT_SAMPLES) {
                StringBuilder sb = new StringBuilder("Dataset import failed: only ")
                        .append(acceptedSamples.size()).append(" quality samples from ")
                        .append(processed).append(" images.");
                if (!rejections.isEmpty()) {
                    sb.append(" Rejections:");
                    rejections.forEach((reason, count) -> sb.append(" [").append(reason).append("]=").append(count));
                }
                throw new IllegalStateException(sb.toString());
            }

            log.info("Dataset import for {}: {} accepted from {} images. Rejections: {}",
                    userId, acceptedSamples.size(), processed, rejections);
            return faceVerificationService.storeEnrollment(userId, acceptedSamples);
        } finally {
            acceptedSamples.forEach(Mat::release);
        }
    }

    public VisionSecurityStatus resetEnrollment(String userId) throws Exception {
        faceVerificationService.resetEnrollment(userId);
        return statusFor(userId);
    }

    public PipelineSnapshotResult capturePipelineSnapshot(String userId) throws Exception {
        return runExclusiveCameraOperation("pipeline snapshot", () -> {
            Instant createdAt = Instant.now();
            Path directory = incidentStore.createSnapshotDirectory(userId, createdAt);
            return cameraCaptureService.withCameraSession("pipeline snapshot", session -> {
                Mat frame = session.captureFrame();
                try {
                    PipelineResult result = visionPipelineService.analyze(userId, frame, directory);
                    return new PipelineSnapshotResult(userId, createdAt, directory.toString(), result);
                } finally {
                    frame.release();
                }
            });
        });
    }

    public EmailDelivery sendTestAlert(String userId) {
        return emailAlertService.sendTestAlert(userId);
    }

    public List<IncidentRecord> listIncidents(String userId, int limit) throws Exception {
        return incidentStore.listIncidents(userId, limit);
    }

    public IncidentRecord incident(String userId, String incidentId) throws Exception {
        IncidentRecord record = incidentStore.loadIncident(userId, incidentId);
        if (record == null) {
            throw new IllegalArgumentException("Incident not found: " + incidentId);
        }
        return record;
    }

    public VisionSecurityConfigView configView() {
        return new VisionSecurityConfigView(
                properties.getMonitoring().getCheckIntervalMs(),
                properties.getMonitoring().getDebounceUnknownFrames(),
                properties.getMonitoring().getAlertCooldownSeconds(),
                properties.getStorage().getRoot(),
                properties.getEmail().getRecipient(),
                properties.getScreen().getOcrLanguage(),
                properties.getGpu().isPreferIfAvailable(),
                screenContextService.detectDisplayServer()
        );
    }

    public boolean isMonitoringEnabled() {
        return monitoringEnabled.get();
    }

    public CapabilityStatus cameraCapabilityStatus() {
        if (monitoringEnabled.get()) {
            return lastCameraStatus;
        }

        String exclusiveOperation = activeExclusiveOperation.get();
        if (exclusiveOperation != null) {
            return new CapabilityStatus("AVAILABLE", "Camera reserved by " + exclusiveOperation);
        }

        return cameraCaptureService.capabilityStatus();
    }

    public VisionSecurityStatus statusFor(String userId) {
        String scopedUser = userId == null || userId.isBlank() ? activeUserId : userId;
        boolean ownerEnrolled = false;
        int incidentCount = 0;
        if (scopedUser != null && !scopedUser.isBlank()) {
            ownerEnrolled = faceVerificationService.isEnrolled(scopedUser);
            try {
                incidentCount = incidentStore.incidentCount(scopedUser);
            } catch (Exception ex) {
                log.warn("Failed to count incidents for {}", scopedUser, ex);
            }
        }

        CapabilityStatus cameraStatus = cameraCapabilityStatus();
        CapabilityStatus screenshotStatus = screenshotService.capabilityStatus();
        CapabilityStatus ocrStatus = ocrService.capabilityStatus();
        CapabilityStatus emailStatus = emailAlertService.capabilityStatus();
        GpuStatus gpuStatus = gpuStatusService.currentStatus();

        return new VisionSecurityStatus(
                deriveServiceStatus(cameraStatus, screenshotStatus, ocrStatus, ownerEnrolled),
                monitoringEnabled.get(),
                activeUserId,
                ownerEnrolled,
                lastDecision,
                lastDecisionAt,
                lastReason,
                lastFaceCount,
                lastUnknownStreak,
                lastIncidentId,
                incidentCount,
                cameraStatus,
                screenshotStatus,
                ocrStatus,
                emailStatus,
                gpuStatus,
                configView()
        );
    }

    private void monitorOnce(String userId) {
        try {
            Mat frame = cameraCaptureService.captureFrame("monitoring");
            try {
                lastCameraStatus = new CapabilityStatus("AVAILABLE", "Camera capture succeeded");
                PipelineResult quickResult = visionPipelineService.analyze(userId, frame, null);
                MonitoringDecisionEngine.Observation observation = decisionEngine.observe(quickResult.decision(), Instant.now());
                updateDecision(quickResult, observation.effectiveDecision());
                lastUnknownStreak = observation.unknownStreak();
                if (observation.alertTriggered()) {
                    recordIncident(userId, frame);
                }
            } finally {
                frame.release();
            }
        } catch (Exception ex) {
            lastCameraStatus = new CapabilityStatus("UNAVAILABLE", ex.getMessage() == null ? "Camera capture failed" : ex.getMessage());
            lastReason = "Monitoring error: " + ex.getMessage();
            log.warn("Vision security monitoring tick failed", ex);
        }
    }

    private void sleepBetweenEnrollmentSamples() {
        try {
            Thread.sleep(properties.getEnrollment().getSampleSpacingMs());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Owner enrollment was interrupted", ex);
        }
    }

    private EnrollmentSampleAssessment assessEnrollmentSample(Mat sample, List<String> acceptedHashes) {
        double sharpness = visionPipelineService.measureFaceSharpness(sample);
        if (sharpness < properties.getEnrollment().getMinFaceSharpness()) {
            return new EnrollmentSampleAssessment(
                    sharpness,
                    0.0,
                    Integer.MAX_VALUE,
                    null,
                    "blurry face (sharpness=" + String.format("%.1f", sharpness) + ")"
            );
        }

        double contrast = visionPipelineService.measureFaceContrast(sample);
        if (contrast < properties.getEnrollment().getMinFaceContrast()) {
            return new EnrollmentSampleAssessment(
                    sharpness,
                    contrast,
                    Integer.MAX_VALUE,
                    null,
                    "low contrast (contrast=" + String.format("%.1f", contrast) + ")"
            );
        }

        String sampleHash = visionPipelineService.computeDifferenceHash(sample);
        int nearestHashDistance = acceptedHashes.stream()
                .mapToInt(existingHash -> visionPipelineService.hammingDistance(sampleHash, existingHash))
                .min()
                .orElse(Integer.MAX_VALUE);
        if (nearestHashDistance <= properties.getEnrollment().getMaxDuplicateHashDistance()) {
            return new EnrollmentSampleAssessment(
                    sharpness,
                    contrast,
                    nearestHashDistance,
                    sampleHash,
                    "too similar to an accepted sample (hashDistance=" + nearestHashDistance + ")"
            );
        }

        return new EnrollmentSampleAssessment(sharpness, contrast, nearestHashDistance, sampleHash, null);
    }

    private <T> T runExclusiveCameraOperation(String operation, ExclusiveCameraOperation<T> operationCallback) throws Exception {
        beginExclusiveCameraOperation(operation);
        try {
            return operationCallback.execute();
        } finally {
            activeExclusiveOperation.compareAndSet(operation, null);
        }
    }

    private synchronized void beginExclusiveCameraOperation(String operation) {
        if (monitoringEnabled.get()) {
            throw new IllegalStateException("Stop monitoring before starting " + operation);
        }

        String currentOperation = activeExclusiveOperation.get();
        if (currentOperation != null) {
            throw new IllegalStateException("Vision security is already using the camera for " + currentOperation);
        }

        activeExclusiveOperation.set(operation);
    }

    private void recordIncident(String userId, Mat frame) throws Exception {
        Instant createdAt = Instant.now();
        Path incidentDirectory = incidentStore.createIncidentDirectory(userId, createdAt);
        PipelineResult exported = visionPipelineService.analyze(userId, frame, incidentDirectory);

        String screenshotPath = null;
        ScreenContextEvidence screenContext = new ScreenContextEvidence("", "", "", List.of("GENERAL_DESKTOP"));
        String ocrTextPath = null;

        try {
            Path screenshot = screenshotService.capture(incidentDirectory.resolve("screenshot.png"));
            screenshotPath = screenshot.toString();
            ScreenContextService.ScreenContextCapture contextCapture = screenContextService.collect(
                    screenshot,
                    incidentDirectory.resolve("screen-ocr.txt")
            );
            screenContext = contextCapture.evidence();
            ocrTextPath = contextCapture.ocrTextPath();
        } catch (Exception ex) {
            screenContext = new ScreenContextEvidence(
                    "",
                    "",
                    "Screen capture failed: " + ex.getMessage(),
                    List.of("GENERAL_DESKTOP")
            );
        }

        String incidentId = incidentDirectory.getFileName().toString();
        IncidentRecord draft = new IncidentRecord(
                incidentId,
                userId,
                createdAt,
                exported.decision(),
                exported.faceCount(),
                exported.reason(),
                screenContext.semanticTags(),
                screenContext,
                exported.stagePaths(),
                incidentDirectory.toString(),
                exported.rawFramePath(),
                screenshotPath,
                ocrTextPath,
                new EmailDelivery(false, false, "Email not attempted yet")
        );

        EmailDelivery delivery = emailAlertService.sendIncidentAlert(draft);
        IncidentRecord incident = new IncidentRecord(
                draft.incidentId(),
                draft.userId(),
                draft.createdAt(),
                draft.decision(),
                draft.faceCount(),
                draft.reason(),
                draft.semanticTags(),
                draft.screenContext(),
                draft.stagePaths(),
                draft.incidentDirectory(),
                draft.webcamPhotoPath(),
                draft.screenshotPath(),
                draft.ocrTextPath(),
                delivery
        );

        incidentStore.saveIncident(incident);
        lastIncidentId = incident.incidentId();
        lastReason = "Unknown person confirmed and incident stored";
    }

    private void updateDecision(PipelineResult result, DecisionType effectiveDecision) {
        lastDecision = effectiveDecision;
        lastDecisionAt = Instant.now();
        lastReason = effectiveDecision == result.decision()
                ? result.reason()
                : "Temporal consensus kept " + effectiveDecision + " while frame-level result was " + result.decision()
                + " (" + result.reason() + ")";
        lastFaceCount = result.faceCount();
    }

    private String deriveServiceStatus(
            CapabilityStatus camera,
            CapabilityStatus screenshot,
            CapabilityStatus ocr,
            boolean ownerEnrolled
    ) {
        if (!"AVAILABLE".equals(camera.state())) {
            return "UNAVAILABLE";
        }
        if (!ownerEnrolled || !"AVAILABLE".equals(screenshot.state()) || !"AVAILABLE".equals(ocr.state())) {
            return "DEGRADED";
        }
        return "READY";
    }

    @FunctionalInterface
    private interface ExclusiveCameraOperation<T> {
        T execute() throws Exception;
    }

    private record EnrollmentSampleAssessment(
            double sharpness,
            double contrast,
            int nearestHashDistance,
            String sampleHash,
            String rejectionReason
    ) {
    }
}
