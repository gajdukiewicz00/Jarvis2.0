package org.jarvis.pccontrol.securitymonitoring.model;

import org.jarvis.pccontrol.model.DesktopSystemInfo;

import java.util.LinkedHashMap;
import java.util.Map;

public record WorkstationMetadata(
        DesktopSystemInfo systemInfo,
        String activeWindowTitle,
        String activeWindowApplication,
        String username,
        Map<String, String> runtimeMetadata) {

    public WorkstationMetadata {
        activeWindowTitle = activeWindowTitle == null ? "" : activeWindowTitle;
        activeWindowApplication = activeWindowApplication == null ? "" : activeWindowApplication;
        username = username == null ? "" : username;
        runtimeMetadata = runtimeMetadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(runtimeMetadata));
    }
}
