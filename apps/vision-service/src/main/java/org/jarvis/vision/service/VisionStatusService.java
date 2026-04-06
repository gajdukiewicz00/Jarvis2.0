package org.jarvis.vision.service;

import org.jarvis.common.vision.VisionConfigStatusResponse;
import org.jarvis.common.vision.VisionHealthResponse;

public interface VisionStatusService {

    VisionHealthResponse health();

    VisionConfigStatusResponse configStatus();
}
