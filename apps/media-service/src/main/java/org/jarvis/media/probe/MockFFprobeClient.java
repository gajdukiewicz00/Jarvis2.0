package org.jarvis.media.probe;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deterministic ffprobe mock for tests and binary-free deployments. Returns a canned
 * fixture: an h264 video, two English audio tracks (a default main track and a
 * commentary track), and one subtitle track. Tests may override the JSON.
 */
@Component
@ConditionalOnProperty(name = "media.ffprobe.mode", havingValue = "mock", matchIfMissing = true)
public class MockFFprobeClient implements FFprobeClient {

    public static final String DEFAULT_FIXTURE = """
            {
              "streams": [
                {"index": 0, "codec_type": "video", "codec_name": "h264",
                 "duration": "1800.000000", "disposition": {"default": 1},
                 "tags": {"language": "und"}},
                {"index": 1, "codec_type": "audio", "codec_name": "aac", "channels": 6,
                 "duration": "1800.000000", "disposition": {"default": 1, "comment": 0},
                 "tags": {"language": "eng", "title": "Main"}},
                {"index": 2, "codec_type": "audio", "codec_name": "aac", "channels": 2,
                 "duration": "1800.000000", "disposition": {"default": 0, "comment": 1},
                 "tags": {"language": "eng", "title": "Director Commentary"}},
                {"index": 3, "codec_type": "subtitle", "codec_name": "subrip",
                 "disposition": {"default": 0}, "tags": {"language": "eng"}}
              ],
              "format": {"duration": "1800.000000", "format_name": "matroska,webm"}
            }
            """;

    private final AtomicReference<String> fixture = new AtomicReference<>(DEFAULT_FIXTURE);

    @Override
    public String probeJson(Path mediaFile) {
        return fixture.get();
    }

    /** Test hook: override the canned JSON returned by this mock. */
    public void setFixture(String json) {
        fixture.set(json);
    }
}
