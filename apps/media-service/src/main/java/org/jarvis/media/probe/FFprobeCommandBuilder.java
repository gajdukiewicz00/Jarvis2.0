package org.jarvis.media.probe;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Builds the ffprobe argument list as a pure function. The media file is always a
 * single, separate list element, so a filename containing spaces, semicolons, or
 * other shell metacharacters can never inject a command — there is no shell.
 */
@Component
public class FFprobeCommandBuilder {

    public List<String> build(String binary, Path mediaFile) {
        return List.of(
                binary,
                "-v", "error",
                "-hide_banner",
                "-print_format", "json",
                "-show_streams",
                "-show_format",
                mediaFile.toString());
    }
}
