package org.jarvis.media.probe;

import java.util.List;

/**
 * Structured probe output: all streams grouped by type plus the deterministically
 * selected main-voice audio stream index.
 */
public record ProbeResult(
        List<MediaStream> video,
        List<MediaStream> audio,
        List<MediaStream> subtitle,
        Integer selectedAudioIndex,
        Double durationSeconds) {

    public ProbeResult {
        video = video == null ? List.of() : List.copyOf(video);
        audio = audio == null ? List.of() : List.copyOf(audio);
        subtitle = subtitle == null ? List.of() : List.copyOf(subtitle);
    }

    public static ProbeResult from(List<MediaStream> streams, Integer selectedAudioIndex) {
        Double duration = streams.stream()
                .map(MediaStream::durationSeconds)
                .filter(d -> d != null)
                .max(Double::compareTo)
                .orElse(null);
        return new ProbeResult(
                streams.stream().filter(MediaStream::isVideo).toList(),
                streams.stream().filter(MediaStream::isAudio).toList(),
                streams.stream().filter(MediaStream::isSubtitle).toList(),
                selectedAudioIndex,
                duration);
    }
}
