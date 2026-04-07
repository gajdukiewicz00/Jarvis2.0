package org.jarvis.visionsecurity.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CameraCaptureService {

    private final VisionSecurityProperties properties;
    private final OpenCvRuntime openCvRuntime;

    public Mat captureFrame() {
        VideoCapture capture = new VideoCapture(properties.getCamera().getDeviceIndex());
        try {
            if (!capture.isOpened()) {
                throw new IllegalStateException("Unable to open camera device " + properties.getCamera().getDeviceIndex());
            }
            capture.set(Videoio.CAP_PROP_FRAME_WIDTH, properties.getCamera().getWidth());
            capture.set(Videoio.CAP_PROP_FRAME_HEIGHT, properties.getCamera().getHeight());

            Mat frame = new Mat();
            if (!capture.read(frame) || frame.empty()) {
                frame.release();
                throw new IllegalStateException("Camera returned an empty frame");
            }
            return frame;
        } finally {
            capture.release();
        }
    }

    public CapabilityStatus capabilityStatus() {
        VideoCapture capture = new VideoCapture(properties.getCamera().getDeviceIndex());
        try {
            if (!capture.isOpened()) {
                return new CapabilityStatus("UNAVAILABLE", "Camera device " + properties.getCamera().getDeviceIndex() + " is not available");
            }
            return new CapabilityStatus("AVAILABLE", "Camera backend " + capture.getBackendName());
        } catch (Exception ex) {
            return new CapabilityStatus("UNAVAILABLE", ex.getMessage());
        } finally {
            capture.release();
        }
    }
}
