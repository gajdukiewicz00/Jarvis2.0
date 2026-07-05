package org.jarvis.media.tts;

import java.nio.file.Path;
import java.util.List;

/**
 * Combines per-segment dub audio clips onto one continuous timeline track. The real
 * implementation shells out to ffmpeg via a safe argument list; the mock writes a
 * placeholder marker, matching the split already used for {@code FFmpegClient}.
 */
public interface DubAudioMerger {

    void merge(List<DubSegmentAudio> segments, Path output);
}
