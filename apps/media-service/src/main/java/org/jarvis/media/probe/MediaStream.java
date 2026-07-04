package org.jarvis.media.probe;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A single media stream detected by ffprobe.
 *
 * @param index       stream index within the container
 * @param type        "video", "audio", or "subtitle"
 * @param codec       codec name (e.g. "h264", "aac", "subrip")
 * @param language    ISO language tag if present, else null
 * @param channels    audio channel count if applicable, else null
 * @param durationSeconds stream/container duration in seconds, else null
 * @param isDefault   true if the container marks this stream as default
 * @param isCommentary true if metadata indicates a commentary/descriptive track
 * @param title       stream title tag if present, else null
 */
public record MediaStream(
        int index,
        String type,
        String codec,
        String language,
        Integer channels,
        Double durationSeconds,
        boolean isDefault,
        boolean isCommentary,
        String title) {

    @JsonIgnore
    public boolean isAudio() {
        return "audio".equals(type);
    }

    @JsonIgnore
    public boolean isSubtitle() {
        return "subtitle".equals(type);
    }

    @JsonIgnore
    public boolean isVideo() {
        return "video".equals(type);
    }
}
