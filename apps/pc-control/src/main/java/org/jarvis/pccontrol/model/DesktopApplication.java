package org.jarvis.pccontrol.model;

import java.util.List;

public record DesktopApplication(
        String desktopId,
        String name,
        List<String> aliases,
        List<String> categories,
        boolean terminal,
        String sourcePath) {

    public DesktopApplication {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        categories = categories == null ? List.of() : List.copyOf(categories);
    }
}
