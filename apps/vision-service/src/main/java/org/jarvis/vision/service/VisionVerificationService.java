package org.jarvis.vision.service;

import org.jarvis.common.vision.VisionVerifyOwnerDebugResponse;
import org.jarvis.common.vision.VisionVerifyOwnerRequest;
import org.jarvis.common.vision.VisionVerifyOwnerResponse;

public interface VisionVerificationService {

    VisionVerifyOwnerResponse verifyOwner(VisionVerifyOwnerRequest request);

    VisionVerifyOwnerDebugResponse verifyOwnerDebug(VisionVerifyOwnerRequest request);
}
