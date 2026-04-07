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
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisionSecurityManager {

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
    private volatile ScheduledFuture<?> monitoringTask;
    private volatile String activeUserId;
    private volatile DecisionType lastDecision;
    private volatile Instant lastDecisionAt;
    private volatile String lastReason = "Monitoring is idle";
    private volatile int lastFaceCount;
    private volatile int lastUnknownStreak;
    private volatile String lastIncidentId;
    private volatile CapabilityStatus lastCameraStatus = new CapabilityStatus("UNKNOWN", "Camera not checked yet");
    private volatile MonitoringDecisionEngine decisionEngine = new MonitoringDecisionEngine(3, Duration.ofSeconds(60));

    @PreDestroy
    public void shutdown() {
        stopMonitoring(null);
        monitoringExecutor.shutdownNow();
    }

    public synchronized VisionSecurityStatus startMonitoring(String userId) {
        stopMonitoring(userId);
        monitoringEnabled.set(true);
        activeUserId = userId;
        decisionEngine = new MonitoringDecisionEngine(
                properties.getMonitoring().getDebounceUnknownFrames(),
                Duration.ofSeconds(properties.getMonitoring().getAlertCooldownSeconds())
        );
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
        monitoringEnabled.set(false);
        activeUserId = null;
        ScheduledFuture<?> current = monitoringTask;
        monitoringTask = null;
        if (current != null) {
            current.cancel(true);
        }
        decisionEngine.reset();
        lastUnknownStreak = 0;
        lastReason = "Monitoring stopped";
        return statusFor(statusUser);
    }

    public EnrollmentResult captureEnrollment(String userId, Integer sampleCount) throws Exception {
        int requestedSamples = sampleCount == null ? properties.getEnrollment().getSampleCount() : sampleCount;
        if (requestedSamples < 3) {
            throw new IllegalArgumentException("Enrollment needs at least 3 samples");
        }

        Instant deadline = Instant.now().plusSeconds(properties.getEnrollment().getCaptureTimeoutSeconds());
        List<Mat> samples = new java.util.ArrayList<>();
        try {
            while (samples.size() < requestedSamples && Instant.now().isBefore(deadline)) {
                Mat frame = cameraCaptureService.captureFrame();
                try {
                    Mat sample = visionPipelineService.extractEnrollmentFace(frame);
                    samples.add(sample);
                    Thread.sleep(properties.getEnrollment().getSampleSpacingMs());
                } catch (IllegalStateException ignored) {
                    // Skip frames until a single clean face is present.
                } finally {
                    frame.release();
                }
            }

            if (samples.size() < requestedSamples) {
                throw new IllegalStateException("Only captured " + samples.size() + " enrollment samples before timeout");
            }

            return faceVerificationService.storeEnrollment(userId, samples);
        } finally {
            samples.forEach(Mat::release);
        }
    }

    public VisionSecurityStatus resetEnrollment(String userId) throws Exception {
        faceVerificationService.resetEnrollment(userId);
        return statusFor(userId);
    }

    public PipelineSnapshotResult capturePipelineSnapshot(String userId) throws Exception {
        Instant createdAt = Instant.now();
        Path directory = incidentStore.createSnapshotDirectory(userId, createdAt);
        Mat frame = cameraCaptureService.captureFrame();
        try {
            PipelineResult result = visionPipelineService.analyze(userId, frame, directory);
            return new PipelineSnapshotResult(userId, createdAt, directory.toString(), result);
        } finally {
            frame.release();
        }
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

        CapabilityStatus cameraStatus = monitoringEnabled.get() ? lastCameraStatus : cameraCaptureService.capabilityStatus();
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
            Mat frame = cameraCaptureService.captureFrame();
            try {
                lastCameraStatus = new CapabilityStatus("AVAILABLE", "Camera capture succeeded");
                PipelineResult quickResult = visionPipelineService.analyze(userId, frame, null);
                updateDecision(quickResult);
                MonitoringDecisionEngine.Observation observation = decisionEngine.observe(quickResult.decision(), Instant.now());
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

    private void updateDecision(PipelineResult result) {
        lastDecision = result.decision();
        lastDecisionAt = Instant.now();
        lastReason = result.reason();
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
}
