package org.jarvis.pccontrol.service.impl;

import org.jarvis.pccontrol.model.DesktopApplication;

import java.util.List;

record DesktopLaunchTarget(DesktopApplication application, List<String> command) {

    DesktopLaunchTarget {
        command = command == null ? List.of() : List.copyOf(command);
    }
}
