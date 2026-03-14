package org.jarvis.pccontrol.service.impl;

import lombok.RequiredArgsConstructor;
import org.jarvis.pccontrol.config.DesktopControlProperties;
import org.jarvis.pccontrol.exception.MissingToolException;
import org.jarvis.pccontrol.model.VolumeState;
import org.jarvis.pccontrol.service.CommandExecutor;
import org.jarvis.pccontrol.service.CommandLocator;
import org.jarvis.pccontrol.service.CommandResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class LinuxAudioControl {

    private static final Pattern PERCENT_PATTERN = Pattern.compile("(\\d{1,3})%");
    private static final Pattern WPCTL_VOLUME_PATTERN = Pattern.compile("Volume:\\s*([0-9]*\\.?[0-9]+)");

    private final CommandExecutor commandExecutor;
    private final CommandLocator commandLocator;
    private final DesktopControlProperties properties;

    public VolumeState getVolume() throws IOException, InterruptedException {
        String backend = resolveBackend();
        return switch (backend) {
            case "wpctl" -> readWpctlVolume();
            case "pactl" -> readPactlVolume();
            case "amixer" -> readAmixerVolume();
            default -> throw new IllegalStateException("Unsupported audio backend: " + backend);
        };
    }

    public VolumeState setVolume(int level) throws IOException, InterruptedException {
        validateLevel(level);
        String backend = resolveBackend();
        switch (backend) {
            case "wpctl" -> requireSuccess(
                    commandExecutor.execute(List.of("wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", level + "%")),
                    "set volume via wpctl");
            case "pactl" -> requireSuccess(
                    commandExecutor.execute(List.of("pactl", "set-sink-volume", "@DEFAULT_SINK@", level + "%")),
                    "set volume via pactl");
            case "amixer" -> requireSuccess(
                    commandExecutor.execute(List.of("amixer", "set", properties.getAmixerControl(), level + "%")),
                    "set volume via amixer");
            default -> throw new IllegalStateException("Unsupported audio backend: " + backend);
        }
        return getVolume();
    }

    public VolumeState changeVolume(int deltaPercent, String direction) throws IOException, InterruptedException {
        int delta = Math.max(1, Math.min(100, deltaPercent));
        String sign = "+".equals(direction) ? "+" : "-";
        String backend = resolveBackend();
        switch (backend) {
            case "wpctl" -> requireSuccess(
                    commandExecutor.execute(List.of("wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", delta + "%" + sign)),
                    "change volume via wpctl");
            case "pactl" -> requireSuccess(
                    commandExecutor.execute(List.of("pactl", "set-sink-volume", "@DEFAULT_SINK@", sign + delta + "%")),
                    "change volume via pactl");
            case "amixer" -> requireSuccess(
                    commandExecutor.execute(List.of("amixer", "set", properties.getAmixerControl(), delta + "%" + sign)),
                    "change volume via amixer");
            default -> throw new IllegalStateException("Unsupported audio backend: " + backend);
        }
        return getVolume();
    }

    public VolumeState mute() throws IOException, InterruptedException {
        String backend = resolveBackend();
        switch (backend) {
            case "wpctl" -> requireSuccess(
                    commandExecutor.execute(List.of("wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "1")),
                    "mute via wpctl");
            case "pactl" -> requireSuccess(
                    commandExecutor.execute(List.of("pactl", "set-sink-mute", "@DEFAULT_SINK@", "1")),
                    "mute via pactl");
            case "amixer" -> requireSuccess(
                    commandExecutor.execute(List.of("amixer", "set", properties.getAmixerControl(), "mute")),
                    "mute via amixer");
            default -> throw new IllegalStateException("Unsupported audio backend: " + backend);
        }
        return getVolume();
    }

    public VolumeState unmute() throws IOException, InterruptedException {
        String backend = resolveBackend();
        switch (backend) {
            case "wpctl" -> requireSuccess(
                    commandExecutor.execute(List.of("wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "0")),
                    "unmute via wpctl");
            case "pactl" -> requireSuccess(
                    commandExecutor.execute(List.of("pactl", "set-sink-mute", "@DEFAULT_SINK@", "0")),
                    "unmute via pactl");
            case "amixer" -> requireSuccess(
                    commandExecutor.execute(List.of("amixer", "set", properties.getAmixerControl(), "unmute")),
                    "unmute via amixer");
            default -> throw new IllegalStateException("Unsupported audio backend: " + backend);
        }
        return getVolume();
    }

    private String resolveBackend() {
        if (commandLocator.isAvailable("wpctl")) {
            return "wpctl";
        }
        if (commandLocator.isAvailable("pactl")) {
            return "pactl";
        }
        if (commandLocator.isAvailable("amixer")) {
            return "amixer";
        }
        throw new MissingToolException("wpctl/pactl/amixer");
    }

    private VolumeState readWpctlVolume() throws IOException, InterruptedException {
        CommandResult result = requireSuccess(
                commandExecutor.execute(List.of("wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@")),
                "read volume via wpctl");
        Matcher matcher = WPCTL_VOLUME_PATTERN.matcher(result.stdout());
        if (!matcher.find()) {
            throw new IOException("Unable to parse wpctl output: " + result.stdout());
        }
        int level = Math.toIntExact(Math.round(Double.parseDouble(matcher.group(1)) * 100));
        boolean muted = result.stdout().toUpperCase(Locale.ROOT).contains("MUTED");
        return new VolumeState(level, muted, "wpctl");
    }

    private VolumeState readPactlVolume() throws IOException, InterruptedException {
        CommandResult volumeResult = requireSuccess(
                commandExecutor.execute(List.of("pactl", "get-sink-volume", "@DEFAULT_SINK@")),
                "read volume via pactl");
        CommandResult muteResult = requireSuccess(
                commandExecutor.execute(List.of("pactl", "get-sink-mute", "@DEFAULT_SINK@")),
                "read mute state via pactl");
        int level = extractPercent(volumeResult.stdout());
        boolean muted = muteResult.stdout().toLowerCase(Locale.ROOT).contains("yes");
        return new VolumeState(level, muted, "pactl");
    }

    private VolumeState readAmixerVolume() throws IOException, InterruptedException {
        CommandResult result = requireSuccess(
                commandExecutor.execute(List.of("amixer", "get", properties.getAmixerControl())),
                "read volume via amixer");
        int level = extractPercent(result.stdout());
        boolean muted = result.stdout().toLowerCase(Locale.ROOT).contains("[off]");
        return new VolumeState(level, muted, "amixer");
    }

    private static CommandResult requireSuccess(CommandResult result, String operation) throws IOException {
        if (result.exitCode() != 0) {
            throw new IOException("Failed to " + operation + ": " + result.stdout());
        }
        return result;
    }

    private static int extractPercent(String output) throws IOException {
        Matcher matcher = PERCENT_PATTERN.matcher(output);
        if (!matcher.find()) {
            throw new IOException("Unable to parse volume output: " + output);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static void validateLevel(int level) {
        if (level < 0 || level > 100) {
            throw new IllegalArgumentException("Volume level must be between 0 and 100");
        }
    }
}
