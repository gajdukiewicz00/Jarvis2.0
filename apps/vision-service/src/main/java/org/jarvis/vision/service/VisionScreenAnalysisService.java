package org.jarvis.vision.service;

import org.jarvis.common.vision.VisionScreenAnalysisRequest;
import org.jarvis.common.vision.VisionScreenAnalysisResponse;

public interface VisionScreenAnalysisService {

    VisionScreenAnalysisResponse analyze(VisionScreenAnalysisRequest request);
}
