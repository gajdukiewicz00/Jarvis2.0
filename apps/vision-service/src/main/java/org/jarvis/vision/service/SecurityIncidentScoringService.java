package org.jarvis.vision.service;

import org.jarvis.common.vision.VisionScreenAnalysisResponse;

public interface SecurityIncidentScoringService {

    SecurityIncidentAssessment assess(IdentitySignal identitySignal,
                                      FaceLivenessAssessment livenessAssessment,
                                      VisionScreenAnalysisResponse screenAnalysis,
                                      boolean operational);
}
