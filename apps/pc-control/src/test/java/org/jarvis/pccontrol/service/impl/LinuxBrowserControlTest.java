package org.jarvis.pccontrol.service.impl;

import org.jarvis.pccontrol.config.DesktopControlProperties;
import org.jarvis.pccontrol.exception.MissingToolException;
import org.jarvis.pccontrol.model.DesktopOperationResponse;
import org.jarvis.pccontrol.service.CommandExecutor;
import org.jarvis.pccontrol.service.CommandLocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinuxBrowserControlTest {

    @Mock
    private CommandExecutor commandExecutor;

    @Mock
    private CommandLocator commandLocator;

    private DesktopControlProperties properties;
    private LinuxBrowserControl browserControl;

    @BeforeEach
    void setUp() {
        properties = new DesktopControlProperties();
        browserControl = new LinuxBrowserControl(commandExecutor, commandLocator, properties);
    }

    @Test
    void openUrlWithoutBrowserUsesXdgOpen() throws Exception {
        when(commandLocator.isAvailable("xdg-open")).thenReturn(true);

        DesktopOperationResponse response = browserControl.openUrl("https://example.com", null);

        assertTrue(response.success());
        assertEquals("default", response.details().get("browser"));
        verify(commandExecutor).start(List.of("xdg-open", "https://example.com"));
    }

    @Test
    void openUrlWithoutBrowserThrowsMissingToolWhenXdgOpenUnavailable() {
        when(commandLocator.isAvailable("xdg-open")).thenReturn(false);

        assertThrows(MissingToolException.class, () -> browserControl.openUrl("https://example.com", ""));
    }

    @Test
    void openUrlRejectsBlankUrl() {
        assertThrows(IllegalArgumentException.class, () -> browserControl.openUrl("", null));
    }

    @Test
    void openUrlRejectsNonHttpScheme() {
        assertThrows(IllegalArgumentException.class, () -> browserControl.openUrl("ftp://example.com", null));
    }

    @Test
    void openUrlRejectsUrlWithoutHost() {
        assertThrows(IllegalArgumentException.class, () -> browserControl.openUrl("https://", null));
    }

    @Test
    void openUrlRejectsMalformedUri() {
        assertThrows(IllegalArgumentException.class, () -> browserControl.openUrl("https://exa mple.com", null));
    }

    @Test
    void openUrlWithSpecificInstalledBrowserLaunchesIt() throws Exception {
        stubAllBrowserCandidatesUnavailableExcept("firefox");

        DesktopOperationResponse response = browserControl.openUrl("https://example.com", "firefox");

        assertTrue(response.success());
        assertEquals("firefox", response.details().get("browser"));
        verify(commandExecutor).start(List.of("firefox", "https://example.com"));
    }

    @Test
    void openUrlWithUnavailableBrowserThrowsIllegalArgumentException() {
        when(commandLocator.isAvailable("firefox")).thenReturn(false);
        when(commandLocator.isAvailable("google-chrome")).thenReturn(false);
        when(commandLocator.isAvailable("chromium")).thenReturn(false);
        when(commandLocator.isAvailable("brave-browser")).thenReturn(false);
        when(commandLocator.isAvailable("microsoft-edge")).thenReturn(false);
        when(commandLocator.isAvailable("microsoft-edge-stable")).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> browserControl.openUrl("https://example.com", "opera"));
    }

    @Test
    void launchBrowserWithNoPreferenceUsesFirstInstalledBrowser() throws Exception {
        stubAllBrowserCandidatesUnavailableExcept("google-chrome");

        DesktopOperationResponse response = browserControl.launchBrowser(null);

        assertTrue(response.success());
        assertEquals("open_app", response.operation());
        assertEquals("Google Chrome", response.details().get("name"));
        verify(commandExecutor).start(List.of("google-chrome"));
    }

    @Test
    void launchBrowserWithSpecificBrowserResolvesDisplayName() throws Exception {
        stubAllBrowserCandidatesUnavailableExcept("brave-browser");

        DesktopOperationResponse response = browserControl.launchBrowser("brave-browser");

        assertEquals("Brave Browser", response.details().get("name"));
    }

    @Test
    void launchBrowserWithMicrosoftEdgeAliasResolvesDisplayName() throws Exception {
        stubAllBrowserCandidatesUnavailableExcept("microsoft-edge");

        DesktopOperationResponse response = browserControl.launchBrowser("microsoft-edge");

        assertEquals("Microsoft Edge", response.details().get("name"));
    }

    private void stubAllBrowserCandidatesUnavailableExcept(String available) {
        for (String candidate : properties.getBrowserCandidates()) {
            when(commandLocator.isAvailable(candidate)).thenReturn(candidate.equals(available));
        }
    }

    @Test
    void launchBrowserThrowsMissingToolWhenNoneInstalled() {
        when(commandLocator.isAvailable(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);

        assertThrows(MissingToolException.class, () -> browserControl.launchBrowser(null));
    }

    @Test
    void detectInstalledBrowsersReturnsDisplayNamesForAvailableBrowsers() {
        when(commandLocator.isAvailable("firefox")).thenReturn(true);
        when(commandLocator.isAvailable("google-chrome")).thenReturn(false);
        when(commandLocator.isAvailable("chromium")).thenReturn(false);
        when(commandLocator.isAvailable("brave-browser")).thenReturn(false);
        when(commandLocator.isAvailable("microsoft-edge")).thenReturn(false);
        when(commandLocator.isAvailable("microsoft-edge-stable")).thenReturn(false);

        List<String> browsers = browserControl.detectInstalledBrowsers();

        assertEquals(List.of("Firefox"), browsers);
    }

    @Test
    void isGenericBrowserAliasRecognizesKnownAliases() {
        assertTrue(browserControl.isGenericBrowserAlias("browser"));
        assertTrue(browserControl.isGenericBrowserAlias("Default Browser"));
        assertTrue(browserControl.isGenericBrowserAlias("web-browser"));
        assertFalse(browserControl.isGenericBrowserAlias("firefox"));
        assertFalse(browserControl.isGenericBrowserAlias(null));
    }
}
