package org.jarvis.pccontrol.service.impl;

import org.jarvis.pccontrol.model.DesktopOperationResponse;
import org.jarvis.pccontrol.model.MouseClickRequest;
import org.jarvis.pccontrol.model.MouseMoveRequest;
import org.jarvis.pccontrol.model.ScrollRequest;
import org.jarvis.pccontrol.model.SendKeysRequest;
import org.jarvis.pccontrol.service.CommandExecutor;
import org.jarvis.pccontrol.service.CommandLocator;
import org.jarvis.pccontrol.service.CommandResult;
import org.jarvis.pccontrol.exception.MissingToolException;
import org.jarvis.pccontrol.exception.UnsupportedDisplayServerException;
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
class LinuxInputControlTest {

    @Mock
    private CommandExecutor commandExecutor;

    @Mock
    private CommandLocator commandLocator;

    private LinuxInputControl inputControl;

    @BeforeEach
    void setUp() {
        inputControl = new LinuxInputControl(commandExecutor, commandLocator, () -> "x11");
    }

    // --- sendKeys ---

    @Test
    void sendKeysBuildsCorrectCommand() throws Exception {
        when(commandLocator.isAvailable("xdotool")).thenReturn(true);
        when(commandExecutor.execute(List.of("xdotool", "key", "ctrl+shift+t")))
                .thenReturn(new CommandResult(0, "", ""));

        DesktopOperationResponse response = inputControl.sendKeys(new SendKeysRequest("ctrl+shift+t", null));

        assertTrue(response.success());
        assertEquals("send_keys", response.operation());
        assertEquals("ctrl+shift+t", response.details().get("keys"));
        verify(commandExecutor).execute(List.of("xdotool", "key", "ctrl+shift+t"));
    }

    @Test
    void sendKeysWithWindowId() throws Exception {
        when(commandLocator.isAvailable("xdotool")).thenReturn(true);
        when(commandExecutor.execute(List.of("xdotool", "key", "--window", "12345", "Return")))
                .thenReturn(new CommandResult(0, "", ""));

        DesktopOperationResponse response = inputControl.sendKeys(new SendKeysRequest("Return", "12345"));

        assertTrue(response.success());
        verify(commandExecutor).execute(List.of("xdotool", "key", "--window", "12345", "Return"));
    }

    @Test
    void sendKeysRejectsBlankKeys() {
        assertThrows(IllegalArgumentException.class, () ->
                inputControl.sendKeys(new SendKeysRequest("", null)));
    }

    @Test
    void sendKeysRejectsNullRequest() {
        assertThrows(IllegalArgumentException.class, () ->
                inputControl.sendKeys(null));
    }

    @Test
    void sendKeysRejectsUnsafeCharacters() {
        assertThrows(IllegalArgumentException.class, () ->
                inputControl.sendKeys(new SendKeysRequest("$(rm -rf /)", null)));
    }

    @Test
    void sendKeysRejectsTooLongInput() {
        String longKeys = "a".repeat(201);
        assertThrows(IllegalArgumentException.class, () ->
                inputControl.sendKeys(new SendKeysRequest(longKeys, null)));
    }

    @Test
    void sendKeysThrowsMissingToolWithoutXdotool() {
        when(commandLocator.isAvailable("xdotool")).thenReturn(false);
        assertThrows(MissingToolException.class, () ->
                inputControl.sendKeys(new SendKeysRequest("Return", null)));
    }

    @Test
    void sendKeysThrowsUnsupportedDisplayServerWhenHeadless() {
        LinuxInputControl headlessControl = new LinuxInputControl(commandExecutor, commandLocator, () -> "headless");

        assertThrows(UnsupportedDisplayServerException.class, () ->
                headlessControl.sendKeys(new SendKeysRequest("Return", null)));
    }

    // --- mouseClick ---

    @Test
    void mouseClickBuildsCorrectCommand() throws Exception {
        when(commandLocator.isAvailable("xdotool")).thenReturn(true);
        when(commandExecutor.execute(List.of(
                "xdotool", "mousemove", "100", "200", "click", "1")))
                .thenReturn(new CommandResult(0, "", ""));

        DesktopOperationResponse response = inputControl.mouseClick(new MouseClickRequest(100, 200, null));

        assertTrue(response.success());
        assertEquals("mouse_click", response.operation());
        assertEquals(100, response.details().get("x"));
        assertEquals(200, response.details().get("y"));
        assertEquals(1, response.details().get("button"));
    }

    @Test
    void mouseClickRightButton() throws Exception {
        when(commandLocator.isAvailable("xdotool")).thenReturn(true);
        when(commandExecutor.execute(List.of(
                "xdotool", "mousemove", "50", "75", "click", "3")))
                .thenReturn(new CommandResult(0, "", ""));

        DesktopOperationResponse response = inputControl.mouseClick(new MouseClickRequest(50, 75, 3));

        assertTrue(response.success());
        assertEquals(3, response.details().get("button"));
    }

    @Test
    void mouseClickRejectsNegativeCoordinates() {
        assertThrows(IllegalArgumentException.class, () ->
                inputControl.mouseClick(new MouseClickRequest(-1, 100, null)));
    }

    @Test
    void mouseClickRejectsInvalidButton() {
        assertThrows(IllegalArgumentException.class, () ->
                inputControl.mouseClick(new MouseClickRequest(0, 0, 6)));
    }

    @Test
    void mouseClickRejectsNullRequest() {
        assertThrows(IllegalArgumentException.class, () ->
                inputControl.mouseClick(null));
    }

    @Test
    void mouseClickThrowsUnsupportedDisplayServerWhenHeadless() {
        LinuxInputControl headlessControl = new LinuxInputControl(commandExecutor, commandLocator, () -> "headless");

        assertThrows(UnsupportedDisplayServerException.class, () ->
                headlessControl.mouseClick(new MouseClickRequest(100, 200, null)));
    }

    // --- mouseMove ---

    @Test
    void mouseMoveBuildsCorrectCommand() throws Exception {
        when(commandLocator.isAvailable("xdotool")).thenReturn(true);
        when(commandExecutor.execute(List.of("xdotool", "mousemove", "500", "300")))
                .thenReturn(new CommandResult(0, "", ""));

        DesktopOperationResponse response = inputControl.mouseMove(new MouseMoveRequest(500, 300));

        assertTrue(response.success());
        assertEquals("mouse_move", response.operation());
        assertEquals(500, response.details().get("x"));
        assertEquals(300, response.details().get("y"));
    }

    @Test
    void mouseMoveRejectsNegativeY() {
        assertThrows(IllegalArgumentException.class, () ->
                inputControl.mouseMove(new MouseMoveRequest(0, -1)));
    }

    // --- scroll ---

    @Test
    void scrollDownBuildsCorrectCommand() throws Exception {
        when(commandLocator.isAvailable("xdotool")).thenReturn(true);
        when(commandExecutor.execute(List.of("xdotool", "click", "--repeat", "3", "5")))
                .thenReturn(new CommandResult(0, "", ""));

        DesktopOperationResponse response = inputControl.scroll(new ScrollRequest(3, "down"));

        assertTrue(response.success());
        assertEquals("scroll", response.operation());
        assertEquals("down", response.details().get("direction"));
        assertEquals(3, response.details().get("amount"));
    }

    @Test
    void scrollUpUsesButton4() throws Exception {
        when(commandLocator.isAvailable("xdotool")).thenReturn(true);
        when(commandExecutor.execute(List.of("xdotool", "click", "--repeat", "5", "4")))
                .thenReturn(new CommandResult(0, "", ""));

        DesktopOperationResponse response = inputControl.scroll(new ScrollRequest(5, "up"));

        assertTrue(response.success());
        verify(commandExecutor).execute(List.of("xdotool", "click", "--repeat", "5", "4"));
    }

    @Test
    void scrollRejectsInvalidDirection() {
        assertThrows(IllegalArgumentException.class, () ->
                inputControl.scroll(new ScrollRequest(1, "diagonal")));
    }

    @Test
    void scrollRejectsZeroAmount() {
        assertThrows(IllegalArgumentException.class, () ->
                inputControl.scroll(new ScrollRequest(0, "down")));
    }

    @Test
    void scrollRejectsExcessiveAmount() {
        assertThrows(IllegalArgumentException.class, () ->
                inputControl.scroll(new ScrollRequest(101, "down")));
    }

    @Test
    void scrollRejectsNullRequest() {
        assertThrows(IllegalArgumentException.class, () ->
                inputControl.scroll(null));
    }

    // --- validateCoordinates ---

    @Test
    void validateCoordinatesAcceptsZero() {
        LinuxInputControl.validateCoordinates(0, 0);
    }

    @Test
    void validateCoordinatesRejectsNegativeX() {
        assertThrows(IllegalArgumentException.class, () ->
                LinuxInputControl.validateCoordinates(-1, 0));
    }
}
