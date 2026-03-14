package org.jarvis.pccontrol.service;

import org.jarvis.pccontrol.model.DesktopCapabilities;

public interface CapabilityDetector {
    DesktopCapabilities detect();
}
