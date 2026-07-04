package org.jarvis.media.asr;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Reads/writes a {@link Transcript} as a JSON workspace artifact so the transcript
 * produced by C4 can be consumed by C5 subtitle generation and C6 dubbing.
 */
@Component
public class TranscriptCodec {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public void write(Path file, Transcript transcript) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), transcript);
        } catch (IOException e) {
            throw new AsrException("Could not write transcript: " + e.getMessage());
        }
    }

    public Transcript read(Path file) {
        try {
            return mapper.readValue(file.toFile(), Transcript.class);
        } catch (IOException e) {
            throw new AsrException("Could not read transcript: " + e.getMessage());
        }
    }
}
