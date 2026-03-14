package org.jarvis.pccontrol.model;

import java.util.List;

public record DesktopSystemInfo(
        String platform,
        String distribution,
        String kernelVersion,
        String architecture,
        String hostname,
        String desktopSession,
        String displayServer,
        List<String> installedBrowsers) {

    public DesktopSystemInfo {
        installedBrowsers = installedBrowsers == null ? List.of() : List.copyOf(installedBrowsers);
    }
}
