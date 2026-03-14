package org.jarvis.pccontrol.service.impl;

import lombok.RequiredArgsConstructor;
import org.jarvis.pccontrol.model.DesktopSystemInfo;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class LinuxSystemInfoProvider {

    private final LinuxBrowserControl browserControl;

    public DesktopSystemInfo getSystemInfo() {
        return new DesktopSystemInfo(
                "linux",
                distribution(),
                System.getProperty("os.version"),
                System.getProperty("os.arch"),
                hostname(),
                firstNonBlank(System.getenv("XDG_CURRENT_DESKTOP"), System.getenv("DESKTOP_SESSION"), "unknown"),
                displayServer(),
                browserControl.detectInstalledBrowsers());
    }

    private String distribution() {
        Path osRelease = Path.of("/etc/os-release");
        if (!Files.isReadable(osRelease)) {
            return System.getProperty("os.name");
        }
        try {
            List<String> lines = Files.readAllLines(osRelease);
            for (String line : lines) {
                if (line.startsWith("PRETTY_NAME=")) {
                    return line.substring("PRETTY_NAME=".length()).replace("\"", "");
                }
            }
        } catch (IOException ignored) {
            return System.getProperty("os.name");
        }
        return System.getProperty("os.name");
    }

    private String hostname() {
        String envHostname = System.getenv("HOSTNAME");
        if (envHostname != null && !envHostname.isBlank()) {
            return envHostname;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown";
        }
    }

    private String displayServer() {
        if (Optional.ofNullable(System.getenv("WAYLAND_DISPLAY")).filter(value -> !value.isBlank()).isPresent()) {
            return "wayland";
        }
        if (Optional.ofNullable(System.getenv("DISPLAY")).filter(value -> !value.isBlank()).isPresent()) {
            return "x11";
        }
        return "headless";
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback.toLowerCase(Locale.ROOT);
    }
}
