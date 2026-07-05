package org.jarvis.media.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses whisper.cpp {@code --output-json} (CLI flag {@code -oj}) transcript output
 * into a {@link Transcript}. Treats the payload defensively: malformed JSON or a
 * missing transcription array raises {@link AsrException} rather than a half-built
 * result, mirroring {@code FFprobeParser}'s posture for ffprobe output.
 *
 * <p>Expected shape (subset actually read):</p>
 * <pre>{@code
 * {
 *   "result": {"language": "en"},
 *   "transcription": [
 *     {"offsets": {"from": 0, "to": 2500}, "text": " Good evening."}
 *   ]
 * }
 * }</pre>
 */
@Component
public class WhisperJsonParser {

    private final ObjectMapper mapper = new ObjectMapper();

    public Transcript parse(String json, String languageHint) {
        if (json == null || json.isBlank()) {
            throw new AsrException("whisper.cpp produced no output to parse");
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new AsrException("whisper.cpp output is not valid JSON: " + e.getMessage());
        }
        String language = resolveLanguage(root, languageHint);

        JsonNode transcription = root.path("transcription");
        List<TranscriptSegment> segments = new ArrayList<>();
        if (transcription.isArray()) {
            int index = 0;
            for (JsonNode segment : transcription) {
                String text = segment.path("text").asText("").trim();
                if (text.isEmpty()) {
                    continue;
                }
                long startMs = segment.path("offsets").path("from").asLong(0);
                long endMs = segment.path("offsets").path("to").asLong(startMs);
                segments.add(new TranscriptSegment(index++, startMs, endMs, text, null, null));
            }
        }
        return new Transcript(language, segments);
    }

    private String resolveLanguage(JsonNode root, String languageHint) {
        String detected = root.path("result").path("language").asText(null);
        if (detected != null && !detected.isBlank()) {
            return detected;
        }
        return (languageHint == null || languageHint.isBlank()) ? "en" : languageHint;
    }
}
