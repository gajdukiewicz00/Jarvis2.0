package org.jarvis.media.probe;

import java.nio.file.Path;

/**
 * Produces ffprobe JSON for a media file. Implementations: a real one shelling out
 * to {@code ffprobe} via a safe argument list, and a mock returning canned JSON.
 */
public interface FFprobeClient {

    /** @return raw ffprobe {@code -print_format json} output for the given file. */
    String probeJson(Path mediaFile);
}
