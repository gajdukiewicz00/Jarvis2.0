package org.jarvis.media.probe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses ffprobe {@code -print_format json} output into typed {@link MediaStream}s.
 * Treats ffprobe output defensively: any malformed or empty payload raises a
 * {@link ProbeException} rather than producing a half-built result.
 */
@Component
public class FFprobeParser {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<MediaStream> parse(String json) {
        if (json == null || json.isBlank()) {
            throw new ProbeException("ffprobe produced no output");
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new ProbeException("ffprobe output is not valid JSON", e);
        }
        JsonNode streams = root.get("streams");
        if (streams == null || !streams.isArray()) {
            throw new ProbeException("ffprobe output has no streams array");
        }
        Double formatDuration = readDouble(root.path("format").path("duration"));

        List<MediaStream> result = new ArrayList<>();
        for (JsonNode s : streams) {
            String type = s.path("codec_type").asText(null);
            if (type == null) {
                continue;
            }
            JsonNode tags = s.path("tags");
            JsonNode disp = s.path("disposition");
            String title = tags.path("title").asText(null);
            boolean commentByDisposition = disp.path("comment").asInt(0) == 1
                    || disp.path("descriptions").asInt(0) == 1;
            boolean commentByTitle = title != null && looksLikeCommentary(title);
            Double duration = readDouble(s.path("duration"));
            result.add(new MediaStream(
                    s.path("index").asInt(result.size()),
                    type,
                    s.path("codec_name").asText(null),
                    emptyToNull(tags.path("language").asText(null)),
                    s.has("channels") ? s.get("channels").asInt() : null,
                    duration != null ? duration : formatDuration,
                    disp.path("default").asInt(0) == 1,
                    commentByDisposition || commentByTitle,
                    title));
        }
        return result;
    }

    private boolean looksLikeCommentary(String title) {
        String t = title.toLowerCase();
        return t.contains("comment") || t.contains("коммент") || t.contains("descriptive")
                || t.contains("description") || t.contains("audio description");
    }

    private Double readDouble(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            String text = node.asText();
            return (text == null || text.isBlank()) ? null : Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
