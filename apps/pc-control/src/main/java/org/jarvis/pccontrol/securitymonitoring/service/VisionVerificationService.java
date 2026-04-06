package org.jarvis.pccontrol.securitymonitoring.service;

import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.pccontrol.securitymonitoring.model.CapturedFrame;

public interface VisionVerificationService {

    VisionVerifyOwnerResponse verifyOwner(CapturedFrame frame);
}
