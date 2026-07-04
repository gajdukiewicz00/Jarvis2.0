package org.jarvis.media.asr;

import java.nio.file.Path;

/**
 * Speech-to-text provider. Implementations may wrap whisper.cpp/Vosk; the default is
 * a deterministic mock. An empty/silent audio file yields an empty transcript;
 * unrecoverable decoding errors raise {@link AsrException}.
 */
public interface AsrProvider {

    Transcript transcribe(Path audioFile, String languageHint);
}
