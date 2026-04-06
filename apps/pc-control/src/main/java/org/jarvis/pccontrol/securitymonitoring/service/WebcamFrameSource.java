package org.jarvis.pccontrol.securitymonitoring.service;

import org.jarvis.pccontrol.securitymonitoring.model.WebcamCaptureResult;

public interface WebcamFrameSource {

    WebcamCaptureResult captureFrame();
}
