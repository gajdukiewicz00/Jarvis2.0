package org.jarvis.vision.pipeline;

import org.jarvis.vision.service.FaceDetectionProvider;

import java.awt.image.BufferedImage;

public interface VisionImagePipelineService {

    VisionPipelineExecution process(BufferedImage originalImage, FaceDetectionProvider faceDetectionProvider);
}
