package org.jarvis.pccontrol.securitymonitoring.service;

import java.awt.image.BufferedImage;

public interface ScreenshotCaptureService {

    BufferedImage captureScreenshot() throws Exception;
}
