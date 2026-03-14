package org.jarvis.pccontrol.service.impl;

import org.jarvis.pccontrol.model.DesktopOperationResponse;
import org.jarvis.pccontrol.model.WindowFocusRequest;
import org.jarvis.pccontrol.model.WindowInfo;
import org.jarvis.pccontrol.model.WindowListResponse;
import org.jarvis.pccontrol.exception.MissingToolException;
import org.jarvis.pccontrol.exception.UnsupportedDisplayServerException;
import org.jarvis.pccontrol.exception.WindowNotFoundException;
import org.jarvis.pccontrol.service.CommandExecutor;
import org.jarvis.pccontrol.service.CommandLocator;
import org.jarvis.pccontrol.service.CommandResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinuxWindowControlTest {

    @Mock
    private CommandExecutor commandExecutor;

    @Mock
    private CommandLocator commandLocator;

    private LinuxWindowControl windowControl;

    @BeforeEach
    void setUp() {
        windowControl = new LinuxWindowControl(commandExecutor, commandLocator, () -> "x11");
    }

    // --- focusWindow ---

    @Test
    void focusWindowByIdUsingWmctrl() throws Exception {
        when(commandLocator.isAvailable("wmctrl")).thenReturn(true);
        when(commandExecutor.execute(List.of("wmctrl", "-i", "-a", "0x04000003")))
                .thenReturn(new CommandResult(0, "", ""));

        DesktopOperationResponse response = windowControl.focusWindow(
                new WindowFocusRequest("0x04000003", null));

        assertTrue(response.success());
        assertEquals("window_focus", response.operation());
        assertEquals("wmctrl", response.details().get("backend"));
        verify(commandExecutor).execute(List.of("wmctrl", "-i", "-a", "0x04000003"));
    }

    @Test
    void focusWindowByNameUsingWmctrl() throws Exception {
        when(commandLocator.isAvailable("wmctrl")).thenReturn(true);
        when(commandExecutor.execute(List.of("wmctrl", "-a", "Firefox")))
                .thenReturn(new CommandResult(0, "", ""));

        DesktopOperationResponse response = windowControl.focusWindow(
                new WindowFocusRequest(null, "Firefox"));

        assertTrue(response.success());
        assertEquals("Firefox", response.details().get("target"));
    }

    @Test
    void focusWindowFallsBackToXdotool() throws Exception {
        when(commandLocator.isAvailable("wmctrl")).thenReturn(false);
        when(commandLocator.isAvailable("xdotool")).thenReturn(true);
        when(commandExecutor.execute(List.of("xdotool", "windowactivate", "0x04000003")))
                .thenReturn(new CommandResult(0, "", ""));

        DesktopOperationResponse response = windowControl.focusWindow(
                new WindowFocusRequest("0x04000003", null));

        assertTrue(response.success());
        assertEquals("xdotool", response.details().get("backend"));
    }

    @Test
    void focusWindowThrowsMissingToolWhenNoToolAvailable() {
        when(commandLocator.isAvailable("wmctrl")).thenReturn(false);
        when(commandLocator.isAvailable("xdotool")).thenReturn(false);

        assertThrows(MissingToolException.class, () ->
                windowControl.focusWindow(new WindowFocusRequest("0x04000003", null)));
    }

    @Test
    void focusWindowThrowsUnsupportedDisplayServerWhenHeadless() {
        LinuxWindowControl headlessControl = new LinuxWindowControl(commandExecutor, commandLocator, () -> "headless");

        assertThrows(UnsupportedDisplayServerException.class, () ->
                headlessControl.focusWindow(new WindowFocusRequest("0x04000003", null)));
    }

    @Test
    void focusWindowRejectsNullRequest() {
        assertThrows(IllegalArgumentException.class, () ->
                windowControl.focusWindow(null));
    }

    @Test
    void focusWindowRejectsBothFieldsBlank() {
        assertThrows(IllegalArgumentException.class, () ->
                windowControl.focusWindow(new WindowFocusRequest("", "")));
    }

    @Test
    void focusWindowRejectsInvalidHexId() {
        assertThrows(IllegalArgumentException.class, () ->
                windowControl.focusWindow(new WindowFocusRequest("0xGGGG", null)));
    }

    @Test
    void focusWindowRejectsNonNumericId() {
        assertThrows(IllegalArgumentException.class, () ->
                windowControl.focusWindow(new WindowFocusRequest("abc", null)));
    }

    @Test
    void focusWindowThrowsWindowNotFoundWhenXdotoolFails() throws Exception {
        when(commandLocator.isAvailable("wmctrl")).thenReturn(false);
        when(commandLocator.isAvailable("xdotool")).thenReturn(true);
        when(commandExecutor.execute(List.of("xdotool", "search", "--name", "MissingApp")))
                .thenReturn(new CommandResult(1, "", ""));

        assertThrows(WindowNotFoundException.class, () ->
                windowControl.focusWindow(new WindowFocusRequest(null, "MissingApp")));
    }

    @Test
    void focusWindowThrowsWindowNotFoundWhenWmctrlFails() throws Exception {
        when(commandLocator.isAvailable("wmctrl")).thenReturn(true);
        when(commandExecutor.execute(List.of("wmctrl", "-a", "MissingApp")))
                .thenReturn(new CommandResult(1, "", ""));

        assertThrows(WindowNotFoundException.class, () ->
                windowControl.focusWindow(new WindowFocusRequest(null, "MissingApp")));
    }

    // --- getActiveWindow ---

    @Test
    void getActiveWindowReturnsWindowInfo() throws Exception {
        when(commandLocator.isAvailable("xdotool")).thenReturn(true);
        when(commandLocator.isAvailable("xprop")).thenReturn(true);
        when(commandExecutor.execute(List.of("xdotool", "getactivewindow")))
                .thenReturn(new CommandResult(0, "67108867", ""));
        when(commandExecutor.execute(List.of("xdotool", "getactivewindow", "getwindowname")))
                .thenReturn(new CommandResult(0, "Terminal", ""));
        when(commandExecutor.execute(List.of("xprop", "-id", "67108867", "WM_CLASS")))
                .thenReturn(new CommandResult(0, "WM_CLASS(STRING) = \"gnome-terminal\", \"Gnome-terminal\"", ""));

        WindowInfo info = windowControl.getActiveWindow();

        assertEquals("67108867", info.windowId());
        assertEquals("Terminal", info.title());
        assertEquals("gnome-terminal Gnome-terminal", info.wmClass());
    }

    @Test
    void getActiveWindowThrowsMissingToolWithoutXdotool() {
        when(commandLocator.isAvailable("xdotool")).thenReturn(false);

        assertThrows(MissingToolException.class, () -> windowControl.getActiveWindow());
    }

    @Test
    void getActiveWindowThrowsUnsupportedDisplayServerWhenHeadless() {
        LinuxWindowControl headlessControl = new LinuxWindowControl(commandExecutor, commandLocator, () -> "headless");

        assertThrows(UnsupportedDisplayServerException.class, () -> headlessControl.getActiveWindow());
    }

    // --- listWindows ---

    @Test
    void listWindowsParsesWmctrlOutput() throws Exception {
        when(commandLocator.isAvailable("wmctrl")).thenReturn(true);
        String output = """
                0x04000003  0 hostname Terminal
                0x04200004  1 hostname Firefox""";
        when(commandExecutor.execute(List.of("wmctrl", "-l")))
                .thenReturn(new CommandResult(0, output, ""));

        WindowListResponse response = windowControl.listWindows();

        assertEquals(2, response.count());
        assertEquals("0x04000003", response.windows().get(0).windowId());
        assertEquals("Terminal", response.windows().get(0).title());
        assertEquals(0, response.windows().get(0).desktop());
        assertEquals("0x04200004", response.windows().get(1).windowId());
        assertEquals("Firefox", response.windows().get(1).title());
        assertEquals(1, response.windows().get(1).desktop());
    }

    @Test
    void listWindowsFallsBackToXdotool() throws Exception {
        when(commandLocator.isAvailable("wmctrl")).thenReturn(false);
        when(commandLocator.isAvailable("xdotool")).thenReturn(true);
        when(commandExecutor.execute(List.of("xdotool", "search", "--name", "")))
                .thenReturn(new CommandResult(0, "12345\n67890", ""));
        when(commandExecutor.execute(List.of("xdotool", "getwindowname", "12345")))
                .thenReturn(new CommandResult(0, "Terminal", ""));
        when(commandExecutor.execute(List.of("xdotool", "getwindowname", "67890")))
                .thenReturn(new CommandResult(0, "Firefox", ""));

        WindowListResponse response = windowControl.listWindows();

        assertEquals(2, response.count());
        assertEquals("Terminal", response.windows().get(0).title());
        assertEquals("Firefox", response.windows().get(1).title());
    }

    @Test
    void listWindowsThrowsMissingToolWhenNoToolAvailable() {
        when(commandLocator.isAvailable("wmctrl")).thenReturn(false);
        when(commandLocator.isAvailable("xdotool")).thenReturn(false);

        assertThrows(MissingToolException.class, () -> windowControl.listWindows());
    }

    @Test
    void listWindowsThrowsUnsupportedDisplayServerWhenHeadless() {
        LinuxWindowControl headlessControl = new LinuxWindowControl(commandExecutor, commandLocator, () -> "headless");

        assertThrows(UnsupportedDisplayServerException.class, () -> headlessControl.listWindows());
    }

    // --- parseWmClass ---

    @Test
    void parseWmClassExtractsClassName() {
        assertEquals("gnome-terminal Gnome-terminal",
                LinuxWindowControl.parseWmClass("WM_CLASS(STRING) = \"gnome-terminal\", \"Gnome-terminal\""));
    }

    @Test
    void parseWmClassReturnsEmptyForMissing() {
        assertEquals("", LinuxWindowControl.parseWmClass(null));
        assertEquals("", LinuxWindowControl.parseWmClass("WM_CLASS: not found."));
    }

    // --- validateWindowId ---

    @Test
    void validateWindowIdAcceptsValidHex() {
        LinuxWindowControl.validateWindowId("0x04000003");
    }

    @Test
    void validateWindowIdAcceptsDecimal() {
        LinuxWindowControl.validateWindowId("67108867");
    }

    @Test
    void validateWindowIdRejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () ->
                LinuxWindowControl.validateWindowId("not-a-window"));
    }
}
