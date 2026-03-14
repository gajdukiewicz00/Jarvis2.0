package org.jarvis.pccontrol.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "pc-control.desktop")
public class DesktopControlProperties {

    private List<String> applicationDirs = new ArrayList<>(List.of(
            "/usr/share/applications",
            Path.of(System.getProperty("user.home"), ".local", "share", "applications").toString()
    ));

    private List<String> browserCandidates = new ArrayList<>(List.of(
            "firefox",
            "google-chrome",
            "chromium",
            "brave-browser",
            "microsoft-edge",
            "microsoft-edge-stable"
    ));

    private String amixerControl = "Master";

    public List<String> getApplicationDirs() {
        return List.copyOf(applicationDirs);
    }

    public void setApplicationDirs(List<String> applicationDirs) {
        this.applicationDirs = applicationDirs == null ? new ArrayList<>() : new ArrayList<>(applicationDirs);
    }

    public List<String> getBrowserCandidates() {
        return List.copyOf(browserCandidates);
    }

    public void setBrowserCandidates(List<String> browserCandidates) {
        this.browserCandidates = browserCandidates == null ? new ArrayList<>() : new ArrayList<>(browserCandidates);
    }

    public String getAmixerControl() {
        return amixerControl;
    }

    public void setAmixerControl(String amixerControl) {
        this.amixerControl = amixerControl;
    }
}
