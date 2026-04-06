package org.jarvis.pccontrol.securitymonitoring.service.impl;

import org.jarvis.pccontrol.securitymonitoring.service.ScreenshotCaptureService;
import org.springframework.stereotype.Component;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;

@Component
public class RobotScreenshotCaptureService implements ScreenshotCaptureService {

    @Override
    public BufferedImage captureScreenshot() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Desktop screenshot capture is unavailable in headless mode");
        }

        Rectangle bounds = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            bounds = bounds.union(device.getDefaultConfiguration().getBounds());
        }
        return new Robot().createScreenCapture(bounds);
    }
}
