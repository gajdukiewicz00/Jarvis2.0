package org.jarvis.pccontrol.securitymonitoring.service;

import org.jarvis.common.vision.VisionVerifyOwnerResponse;
import org.jarvis.pccontrol.securitymonitoring.model.CapturedFrame;
import org.jarvis.pccontrol.securitymonitoring.model.EvidenceBundle;
import org.jarvis.pccontrol.securitymonitoring.model.MonitoringDecision;
import org.jarvis.pccontrol.securitymonitoring.model.ScreenObservation;

import java.util.List;

public interface EvidenceCollector {

    EvidenceBundle collect(String trigger,
                           CapturedFrame frame,
                           VisionVerifyOwnerResponse verificationResult,
                           ScreenObservation screenObservation,
                           MonitoringDecision decision,
                           List<String> warnings) throws Exception;
}
