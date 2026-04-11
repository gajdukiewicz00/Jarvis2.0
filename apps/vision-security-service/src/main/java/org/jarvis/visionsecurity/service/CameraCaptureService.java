package org.jarvis.visionsecurity.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
public class CameraCaptureService {

    private static final int CAMERA_OPEN_ATTEMPTS = 3;
    private static final long CAMERA_OPEN_RETRY_DELAY_MS = 150L;
    private static final long CAMERA_LOCK_TIMEOUT_SECONDS = 5L;

    private final VisionSecurityProperties properties;
    private final ReentrantLock cameraLock = new ReentrantLock(true);
    private final AtomicReference<String> activeOperation = new AtomicReference<>();

    public Mat captureFrame() {
        return captureFrame("vision security request");
    }

    public Mat captureFrame(String operation) {
        try {
            return withCameraSession(operation, CameraSession::captureFrame);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Camera capture failed for " + normalizeOperation(operation), ex);
        }
    }

    public <T> T withCameraSession(String operation, CameraSessionCallback<T> callback) throws Exception {
        String normalizedOperation = normalizeOperation(operation);
        acquireCamera(normalizedOperation);
        try (CameraSession session = openSession(normalizedOperation)) {
            return callback.execute(session);
        } finally {
            activeOperation.set(null);
            cameraLock.unlock();
        }
    }

    public CapabilityStatus capabilityStatus() {
        if (!cameraLock.tryLock()) {
            return new CapabilityStatus("AVAILABLE", "Camera reserved by " + describeActiveOperation());
        }

        activeOperation.set("capability check");
        try (CameraSession session = openSession("capability check")) {
            return new CapabilityStatus("AVAILABLE", "Camera backend " + session.backendName());
        } catch (Exception ex) {
            return new CapabilityStatus("UNAVAILABLE", ex.getMessage());
        } finally {
            activeOperation.set(null);
            cameraLock.unlock();
        }
    }

    private void acquireCamera(String operation) {
        boolean acquired;
        try {
            acquired = cameraLock.tryLock(CAMERA_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for camera access for " + operation, ex);
        }

        if (!acquired) {
            throw new IllegalStateException(
                    "Camera is busy with " + describeActiveOperation() + ". Try again when the current vision task finishes."
            );
        }

        activeOperation.set(operation);
    }

    private CameraSession openSession(String operation) {
        IllegalStateException lastFailure = null;
        for (int attempt = 1; attempt <= CAMERA_OPEN_ATTEMPTS; attempt++) {
            VideoCapture capture = new VideoCapture(properties.getCamera().getDeviceIndex());
            if (capture.isOpened()) {
                capture.set(Videoio.CAP_PROP_FRAME_WIDTH, properties.getCamera().getWidth());
                capture.set(Videoio.CAP_PROP_FRAME_HEIGHT, properties.getCamera().getHeight());
                return new CameraSession(capture);
            }

            capture.release();
            lastFailure = buildOpenFailure(operation, attempt);
            if (attempt < CAMERA_OPEN_ATTEMPTS) {
                pauseBeforeRetry(operation);
            }
        }

        throw lastFailure == null ? buildOpenFailure(operation, CAMERA_OPEN_ATTEMPTS) : lastFailure;
    }

    private IllegalStateException buildOpenFailure(String operation, int attempt) {
        String suffix = attempt >= CAMERA_OPEN_ATTEMPTS
                ? "after " + CAMERA_OPEN_ATTEMPTS + " attempts"
                : "on attempt " + attempt + " of " + CAMERA_OPEN_ATTEMPTS;
        return new IllegalStateException(
                "Unable to open camera device " + properties.getCamera().getDeviceIndex() + " for " + operation + " " + suffix
        );
    }

    private void pauseBeforeRetry(String operation) {
        try {
            Thread.sleep(CAMERA_OPEN_RETRY_DELAY_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying camera access for " + operation, ex);
        }
    }

    private String describeActiveOperation() {
        return normalizeOperation(activeOperation.get());
    }

    private String normalizeOperation(String operation) {
        return operation == null || operation.isBlank() ? "another vision security task" : operation;
    }

    @FunctionalInterface
    public interface CameraSessionCallback<T> {
        T execute(CameraSession session) throws Exception;
    }

    public static final class CameraSession implements AutoCloseable {
        private final VideoCapture capture;

        private CameraSession(VideoCapture capture) {
            this.capture = capture;
        }

        public Mat captureFrame() {
            Mat frame = new Mat();
            if (!capture.read(frame) || frame.empty()) {
                frame.release();
                throw new IllegalStateException("Camera returned an empty frame");
            }
            return frame;
        }

        public String backendName() {
            return capture.getBackendName();
        }

        @Override
        public void close() {
            capture.release();
        }
    }
}
