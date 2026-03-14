package org.jarvis.pccontrol.service;

import org.jarvis.pccontrol.model.PcActionRequest;
import org.jarvis.pccontrol.model.PcActionResult;

public interface PcActionExecutionService {

    PcActionResult execute(PcActionRequest request);
}
