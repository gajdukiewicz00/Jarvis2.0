package org.jarvis.pccontrol.service.impl;

import org.jarvis.pccontrol.config.DesktopControlProperties;
import org.jarvis.pccontrol.model.DesktopApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopEntryApplicationCatalogTest {

    @TempDir
    Path tempDir;

    @Test
    void discoversApplicationsFromConfiguredDirectoriesAndExtractsCategories() throws IOException {
        Path systemApps = Files.createDirectories(tempDir.resolve("system-apps"));
        Path userApps = Files.createDirectories(tempDir.resolve("user-apps"));

        writeDesktopEntry(systemApps.resolve("firefox.desktop"), """
                [Desktop Entry]
                Type=Application
                Name=Firefox Web Browser
                Exec=firefox %u
                Categories=Network;WebBrowser;
                Terminal=false
                """);
        writeDesktopEntry(userApps.resolve("hidden.desktop"), """
                [Desktop Entry]
                Type=Application
                Name=Hidden Tool
                Exec=hidden-tool
                NoDisplay=true
                """);

        DesktopEntryApplicationCatalog catalog = new DesktopEntryApplicationCatalog(properties(systemApps, userApps));

        List<DesktopApplication> applications = catalog.listApplications();

        assertEquals(1, applications.size());
        DesktopApplication firefox = applications.getFirst();
        assertEquals("firefox.desktop", firefox.desktopId());
        assertEquals("Firefox Web Browser", firefox.name());
        assertEquals(List.of("Network", "WebBrowser"), firefox.categories());
        assertTrue(firefox.aliases().contains("Firefox Web Browser"));
        assertTrue(firefox.aliases().contains("firefox"));
        assertFalse(firefox.terminal());
    }

    @Test
    void resolvesApplicationByAliasAndSanitizesExecCommand() throws IOException {
        Path systemApps = Files.createDirectories(tempDir.resolve("system-apps"));

        writeDesktopEntry(systemApps.resolve("code.desktop"), """
                [Desktop Entry]
                Type=Application
                Name=Visual Studio Code
                Exec=env BAMF_DESKTOP_FILE_HINT=/usr/share/applications/code.desktop /usr/bin/code --unity-launch %F
                Categories=Development;IDE;
                Terminal=false
                """);

        DesktopEntryApplicationCatalog catalog = new DesktopEntryApplicationCatalog(properties(systemApps));

        DesktopLaunchTarget target = catalog.resolve("code");

        assertEquals("Visual Studio Code", target.application().name());
        assertEquals(
                List.of(
                        "env",
                        "BAMF_DESKTOP_FILE_HINT=/usr/share/applications/code.desktop",
                        "/usr/bin/code",
                        "--unity-launch"
                ),
                target.command()
        );
    }

    @Test
    void rejectsUnsafeApplicationQuery() {
        DesktopEntryApplicationCatalog catalog = new DesktopEntryApplicationCatalog(properties(tempDir));

        assertThrows(IllegalArgumentException.class, () -> catalog.resolve("firefox; rm -rf /"));
    }

    private DesktopControlProperties properties(Path... applicationDirs) {
        DesktopControlProperties properties = new DesktopControlProperties();
        properties.setApplicationDirs(List.of(applicationDirs).stream().map(Path::toString).toList());
        return properties;
    }

    private void writeDesktopEntry(Path path, String content) throws IOException {
        Files.writeString(path, content);
    }
}
