package org.jarvis.media.tts;

import java.nio.file.Path;

/**
 * Synthesizes speech for one segment to {@code output}. The default is a neutral
 * Russian synthetic voice; no real-person voice cloning is implemented.
 */
public interface TtsProvider {

    TtsResult synthesize(String text, VoiceProfile profile, Path output);
}
