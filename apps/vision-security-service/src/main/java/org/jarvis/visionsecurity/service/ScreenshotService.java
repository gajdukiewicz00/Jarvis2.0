package org.jarvis.visionsecurity.service;

import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class ScreenshotService {

    private final ShellCommandRunner commandRunner;

    public ScreenshotService(ShellCommandRunner commandRunner) {
        this.commandRunner = commandRunner;
    }

    public CapabilityStatus capabilityStatus() {
        if (commandRunner.isAvailable("gnome-screenshot")) {
            return new CapabilityStatus("AVAILABLE", "Using gnome-screenshot");
        }
        if (commandRunner.isAvailable("scrot")) {
            return new CapabilityStatus("AVAILABLE", "Using scrot");
        }
        if (commandRunner.isAvailable("import")) {
            return new CapabilityStatus("AVAILABLE", "Using ImageMagick import");
        }
        if (!GraphicsEnvironment.isHeadless()) {
            return new CapabilityStatus("AVAILABLE", "Using Java Robot fallback");
        }
        return new CapabilityStatus("UNAVAILABLE", "No screenshot backend is available in the current session");
    }

    public Path capture(Path target) throws Exception {
        Files.createDirectories(target.getParent());

        if (commandRunner.isAvailable("gnome-screenshot")) {
            executeAndValidate(List.of("gnome-screenshot", "-f", target.toString()), target);
            return target;
        }
        if (commandRunner.isAvailable("scrot")) {
            executeAndValidate(List.of("scrot", target.toString()), target);
            return target;
        }
        if (commandRunner.isAvailable("import")) {
            executeAndValidate(List.of("import", "-window", "root", target.toString()), target);
            return target;
        }
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Cannot capture screenshot in a headless session");
        }

        BufferedImage image = new Robot().createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
        if (!ImageIO.write(image, "png", target.toFile())) {
            throw new IOException("Failed to write screenshot PNG to " + target);
        }
        return target;
    }

    private void executeAndValidate(List<String> command, Path expectedOutput) throws Exception {
        ShellCommandRunner.CommandResult result = commandRunner.execute(command);
        if (result.exitCode() != 0) {
            throw new IOException("Screenshot command failed: " + result.output());
        }
        if (!Files.isRegularFile(expectedOutput)) {
            throw new IOException("Screenshot backend reported success but file was not created: " + expectedOutput);
        }
    }
}
