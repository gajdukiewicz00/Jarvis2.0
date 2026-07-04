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
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void setVolumeUsesPactlWhenWpctlUnavailable() throws IOException, InterruptedException {
        when(commandLocator.isAvailable("wpctl")).thenReturn(false);
        when(commandLocator.isAvailable("pactl")).thenReturn(true);
        when(commandExecutor.execute(List.of("pactl", "set-sink-volume", "@DEFAULT_SINK@", "30%")))
                .thenReturn(new CommandResult(0, "", ""));
        when(commandExecutor.execute(List.of("pactl", "get-sink-volume", "@DEFAULT_SINK@")))
                .thenReturn(new CommandResult(0, "Volume: front-left: 30%", ""));
        when(commandExecutor.execute(List.of("pactl", "get-sink-mute", "@DEFAULT_SINK@")))
                .thenReturn(new CommandResult(0, "Mute: no", ""));

        VolumeState state = audioControl.setVolume(30);

        assertEquals(30, state.level());
        assertEquals("pactl", state.backend());
    }

    @Test
    void setVolumeUsesAmixerWhenOnlyAmixerAvailable() throws IOException, InterruptedException {
        DesktopControlProperties properties = new DesktopControlProperties();
        LinuxAudioControl control = new LinuxAudioControl(commandExecutor, commandLocator, properties);
        when(commandLocator.isAvailable("wpctl")).thenReturn(false);
        when(commandLocator.isAvailable("pactl")).thenReturn(false);
        when(commandLocator.isAvailable("amixer")).thenReturn(true);
        when(commandExecutor.execute(List.of("amixer", "set", "Master", "55%")))
                .thenReturn(new CommandResult(0, "", ""));
        when(commandExecutor.execute(List.of("amixer", "get", "Master")))
                .thenReturn(new CommandResult(0, "Front Left: Playback 55 [55%] [on]", ""));

        VolumeState state = control.setVolume(55);

        assertEquals(55, state.level());
        assertFalse(state.muted());
        assertEquals("amixer", state.backend());
    }

    @Test
    void setVolumeRejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> audioControl.setVolume(-1));
    }

    @Test
    void muteUsesWpctl() throws IOException, InterruptedException {
        when(commandLocator.isAvailable("wpctl")).thenReturn(true);
        when(commandExecutor.execute(List.of("wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "1")))
                .thenReturn(new CommandResult(0, "", ""));
        when(commandExecutor.execute(List.of("wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@")))
                .thenReturn(new CommandResult(0, "Volume: 0.50 [MUTED]", ""));

        VolumeState state = audioControl.mute();

        assertTrue(state.muted());
        verify(commandExecutor).execute(List.of("wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "1"));
    }

    @Test
    void unmuteUsesPactlWhenWpctlUnavailable() throws IOException, InterruptedException {
        when(commandLocator.isAvailable("wpctl")).thenReturn(false);
        when(commandLocator.isAvailable("pactl")).thenReturn(true);
        when(commandExecutor.execute(List.of("pactl", "set-sink-mute", "@DEFAULT_SINK@", "0")))
                .thenReturn(new CommandResult(0, "", ""));
        when(commandExecutor.execute(List.of("pactl", "get-sink-volume", "@DEFAULT_SINK@")))
                .thenReturn(new CommandResult(0, "Volume: front-left: 50%", ""));
        when(commandExecutor.execute(List.of("pactl", "get-sink-mute", "@DEFAULT_SINK@")))
                .thenReturn(new CommandResult(0, "Mute: no", ""));

        VolumeState state = audioControl.unmute();

        assertFalse(state.muted());
        verify(commandExecutor).execute(List.of("pactl", "set-sink-mute", "@DEFAULT_SINK@", "0"));
    }

    @Test
    void changeVolumeUsesAmixerWithMinusDirection() throws IOException, InterruptedException {
        DesktopControlProperties properties = new DesktopControlProperties();
        LinuxAudioControl control = new LinuxAudioControl(commandExecutor, commandLocator, properties);
        when(commandLocator.isAvailable("wpctl")).thenReturn(false);
        when(commandLocator.isAvailable("pactl")).thenReturn(false);
        when(commandLocator.isAvailable("amixer")).thenReturn(true);
        when(commandExecutor.execute(List.of("amixer", "set", "Master", "10%-")))
                .thenReturn(new CommandResult(0, "", ""));
        when(commandExecutor.execute(List.of("amixer", "get", "Master")))
                .thenReturn(new CommandResult(0, "Front Left: Playback 40 [40%] [off]", ""));

        VolumeState state = control.changeVolume(10, "-");

        assertEquals(40, state.level());
        assertTrue(state.muted());
        verify(commandExecutor).execute(List.of("amixer", "set", "Master", "10%-"));
    }

    @Test
    void changeVolumeClampsDeltaToMaximumOfOneHundred() throws IOException, InterruptedException {
        when(commandLocator.isAvailable("wpctl")).thenReturn(true);
        when(commandExecutor.execute(List.of("wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", "100%+")))
                .thenReturn(new CommandResult(0, "", ""));
        when(commandExecutor.execute(List.of("wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@")))
                .thenReturn(new CommandResult(0, "Volume: 1.00", ""));

        audioControl.changeVolume(500, "+");

        verify(commandExecutor).execute(List.of("wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", "100%+"));
    }

    @Test
    void requireSuccessThrowsIOExceptionWhenCommandFails() throws IOException, InterruptedException {
        when(commandLocator.isAvailable("wpctl")).thenReturn(true);
        when(commandExecutor.execute(List.of("wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@")))
                .thenReturn(new CommandResult(1, "error", ""));

        assertThrows(IOException.class, () -> audioControl.getVolume());
    }

    @Test
    void readWpctlVolumeThrowsIOExceptionWhenOutputUnparseable() throws IOException, InterruptedException {
        when(commandLocator.isAvailable("wpctl")).thenReturn(true);
        when(commandExecutor.execute(List.of("wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@")))
                .thenReturn(new CommandResult(0, "garbage output", ""));

        assertThrows(IOException.class, () -> audioControl.getVolume());
    }

    @Test
    void readPactlVolumeThrowsIOExceptionWhenOutputUnparseable() throws IOException, InterruptedException {
        when(commandLocator.isAvailable("wpctl")).thenReturn(false);
        when(commandLocator.isAvailable("pactl")).thenReturn(true);
        when(commandExecutor.execute(List.of("pactl", "get-sink-volume", "@DEFAULT_SINK@")))
                .thenReturn(new CommandResult(0, "garbage", ""));
        when(commandExecutor.execute(List.of("pactl", "get-sink-mute", "@DEFAULT_SINK@")))
                .thenReturn(new CommandResult(0, "Mute: no", ""));

        assertThrows(IOException.class, () -> audioControl.getVolume());
    }
}
