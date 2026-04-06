package org.jarvis.vision.service;

import org.jarvis.common.vision.VisionOwnerReferenceEnrollRequest;
import org.jarvis.common.vision.VisionOwnerReferenceEnrollResponse;

public interface OwnerReferenceService {

    int countReferences();

    VisionOwnerReferenceEnrollResponse enroll(VisionOwnerReferenceEnrollRequest request) throws Exception;
}
