package org.jarvis.pccontrol.securitymonitoring.service.impl;

import com.github.sarxos.webcam.Webcam;
import lombok.RequiredArgsConstructor;
import org.jarvis.pccontrol.securitymonitoring.config.SecurityMonitoringProperties;
import org.jarvis.pccontrol.securitymonitoring.model.CapturedFrame;
import org.jarvis.pccontrol.securitymonitoring.model.WebcamCaptureResult;
import org.jarvis.pccontrol.securitymonitoring.service.WebcamFrameSource;
import org.springframework.stereotype.Component;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DefaultWebcamFrameSource implements WebcamFrameSource {

    private static final String PROVIDER = "webcam-capture";

    private final SecurityMonitoringProperties properties;

    @Override
    public WebcamCaptureResult captureFrame() {
        try {
            List<Webcam> webcams = Webcam.getWebcams();
            int deviceIndex = properties.getWebcam().getDeviceIndex();
            if (webcams.isEmpty() || deviceIndex >= webcams.size()) {
                return new WebcamCaptureResult(false, PROVIDER,
                        "No webcam available for index " + deviceIndex, null);
            }

            Webcam webcam = webcams.get(deviceIndex);
            webcam.setViewSize(new Dimension(
                    properties.getWebcam().getCaptureWidth(),
                    properties.getWebcam().getCaptureHeight()));
            try {
                webcam.open();
                BufferedImage image = null;
                for (int i = 0; i < properties.getWebcam().getWarmupFrames(); i++) {
                    image = webcam.getImage();
                }
                if (image == null) {
                    return new WebcamCaptureResult(false, PROVIDER, "Webcam returned an empty frame", null);
                }
                return new WebcamCaptureResult(
                        true,
                        PROVIDER,
                        "Captured webcam frame successfully",
                        new CapturedFrame(image, PROVIDER, webcam.getName(), Instant.now()));
            } finally {
                webcam.close();
            }
        } catch (Exception exception) {
            return new WebcamCaptureResult(false, PROVIDER, exception.getMessage(), null);
        }
    }
}
