package org.jarvis.pccontrol.securitymonitoring.service.impl;

import lombok.RequiredArgsConstructor;
import org.jarvis.pccontrol.model.DesktopSystemInfo;
import org.jarvis.pccontrol.model.WindowInfo;
import org.jarvis.pccontrol.securitymonitoring.model.WorkstationMetadata;
import org.jarvis.pccontrol.securitymonitoring.service.WorkstationMetadataProvider;
import org.jarvis.pccontrol.service.DesktopControlService;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DefaultWorkstationMetadataProvider implements WorkstationMetadataProvider {

    private final DesktopControlService desktopControlService;
    private final Environment environment;

    @Override
    public WorkstationMetadata collect() {
        DesktopSystemInfo systemInfo = desktopControlService.getSystemInfo();
        WindowInfo activeWindow = activeWindow();
        Map<String, String> runtimeMetadata = new LinkedHashMap<>();
        runtimeMetadata.put("springApplication", environment.getProperty("spring.application.name", "pc-control"));
        runtimeMetadata.put("pcControlStubMode", environment.getProperty("pc-control.stub-mode", "false"));
        runtimeMetadata.put("javaVersion", System.getProperty("java.version", ""));
        runtimeMetadata.put("userTimezone", System.getProperty("user.timezone", ""));
        runtimeMetadata.put("display", System.getenv().getOrDefault("DISPLAY", ""));
        runtimeMetadata.put("waylandDisplay", System.getenv().getOrDefault("WAYLAND_DISPLAY", ""));

        return new WorkstationMetadata(
                systemInfo,
                activeWindow == null ? "" : activeWindow.title(),
                activeWindow == null ? "" : activeWindow.wmClass(),
                System.getProperty("user.name", ""),
                runtimeMetadata);
    }

    private WindowInfo activeWindow() {
        try {
            return desktopControlService.getActiveWindow();
        } catch (Exception exception) {
            return null;
        }
    }
}
