package org.jarvis.pccontrol.service.impl;

import org.jarvis.pccontrol.model.DesktopSystemInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * LinuxSystemInfoProvider reads real environment variables, /etc/os-release, and the
 * local hostname rather than an injectable abstraction, so exact values are not
 * portable across machines/CI. These tests assert the structural contract (fields
 * are populated, displayServer is one of the known values) instead of hard-coding
 * this machine's distro/hostname, and verify the one real collaborator
 * (LinuxBrowserControl) is delegated to correctly.
 */
@ExtendWith(MockitoExtension.class)
class LinuxSystemInfoProviderTest {

    @Mock
    private LinuxBrowserControl browserControl;

    private LinuxSystemInfoProvider provider;

    @BeforeEach
    void setUp() {
        provider = new LinuxSystemInfoProvider(browserControl);
    }

    @Test
    void getSystemInfoReturnsLinuxPlatformAndDelegatesBrowserDetection() {
        when(browserControl.detectInstalledBrowsers()).thenReturn(List.of("Firefox", "Google Chrome"));

        DesktopSystemInfo info = provider.getSystemInfo();

        assertEquals("linux", info.platform());
        assertEquals(List.of("Firefox", "Google Chrome"), info.installedBrowsers());
        assertNotNull(info.distribution());
        assertFalse(info.distribution().isBlank());
        assertNotNull(info.hostname());
        assertFalse(info.hostname().isBlank());
        assertNotNull(info.desktopSession());
        assertTrue(Set.of("wayland", "x11", "headless").contains(info.displayServer()));
        assertEquals(System.getProperty("os.version"), info.kernelVersion());
        assertEquals(System.getProperty("os.arch"), info.architecture());
    }

    @Test
    void getSystemInfoReturnsEmptyBrowserListWhenNoneInstalled() {
        when(browserControl.detectInstalledBrowsers()).thenReturn(List.of());

        DesktopSystemInfo info = provider.getSystemInfo();

        assertTrue(info.installedBrowsers().isEmpty());
    }
}
