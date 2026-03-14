package org.jarvis.pccontrol.service.impl;

import org.jarvis.pccontrol.config.DesktopControlProperties;
import org.jarvis.pccontrol.exception.MissingToolException;
import org.jarvis.pccontrol.model.VolumeState;
import org.jarvis.pccontrol.service.CommandExecutor;
import org.jarvis.pccontrol.service.CommandLocator;
import org.jarvis.pccontrol.service.CommandResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LinuxAudioControlTest {

    @Mock
    private CommandExecutor commandExecutor;

    @Mock
    private CommandLocator commandLocator;

    private LinuxAudioControl audioControl;

    @BeforeEach
    void setUp() {
        audioControl = new LinuxAudioControl(commandExecutor, commandLocator, new DesktopControlProperties());
    }

    @Test
    void fallsBackToPactlWhenWpctlIsUnavailable() throws IOException, InterruptedException {
        when(commandLocator.isAvailable("wpctl")).thenReturn(false);
        when(commandLocator.isAvailable("pactl")).thenReturn(true);
        when(commandExecutor.execute(List.of("pactl", "get-sink-volume", "@DEFAULT_SINK@")))
                .thenReturn(new CommandResult(0,
                        "Volume: front-left: 42598 /  65% / -11.32 dB, front-right: 42598 /  65% / -11.32 dB",
                        ""));
        when(commandExecutor.execute(List.of("pactl", "get-sink-mute", "@DEFAULT_SINK@")))
                .thenReturn(new CommandResult(0, "Mute: no", ""));

        VolumeState state = audioControl.getVolume();

        assertEquals(65, state.level());
        assertFalse(state.muted());
        assertEquals("pactl", state.backend());
    }

    @Test
    void setVolumeUsesWpctlWhenAvailable() throws IOException, InterruptedException {
        when(commandLocator.isAvailable("wpctl")).thenReturn(true);
        when(commandExecutor.execute(List.of("wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", "42%")))
                .thenReturn(new CommandResult(0, "", ""));
        when(commandExecutor.execute(List.of("wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@")))
                .thenReturn(new CommandResult(0, "Volume: 0.42", ""));

        VolumeState state = audioControl.setVolume(42);

        assertEquals(42, state.level());
        assertFalse(state.muted());
        assertEquals("wpctl", state.backend());
        verify(commandExecutor).execute(List.of("wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", "42%"));
    }

    @Test
    void rejectsOutOfRangeVolumeLevel() {
        assertThrows(IllegalArgumentException.class, () -> audioControl.setVolume(101));
    }

    @Test
    void throwsMissingToolWhenNoAudioBackendAvailable() {
        when(commandLocator.isAvailable("wpctl")).thenReturn(false);
        when(commandLocator.isAvailable("pactl")).thenReturn(false);
        when(commandLocator.isAvailable("amixer")).thenReturn(false);

        assertThrows(MissingToolException.class, () -> audioControl.getVolume());
    }
}
