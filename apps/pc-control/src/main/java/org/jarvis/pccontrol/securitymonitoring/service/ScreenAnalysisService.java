package org.jarvis.pccontrol.securitymonitoring.service;

import org.jarvis.pccontrol.securitymonitoring.model.CapturedFrame;
import org.jarvis.pccontrol.securitymonitoring.model.ScreenObservation;

public interface ScreenAnalysisService {

    ScreenObservation observe(CapturedFrame frame);
}
