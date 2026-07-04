package org.jarvis.media.subtitle;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Renders translated cues to SRT and WebVTT, preserving exact timing. Cue numbering
 * is sequential over the supplied cues. Timestamps use SRT's {@code HH:MM:SS,mmm} and
 * VTT's {@code HH:MM:SS.mmm} forms.
 */
@Component
public class SubtitleFormatter {

    public String toSrt(List<TranslatedSegment> cues) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (TranslatedSegment cue : cues) {
            sb.append(n++).append('\n');
            sb.append(srtTime(cue.startMs())).append(" --> ").append(srtTime(cue.endMs())).append('\n');
            sb.append(cue.text() == null ? "" : cue.text().trim()).append('\n');
            sb.append('\n');
        }
        return sb.toString();
    }

    public String toVtt(List<TranslatedSegment> cues) {
        StringBuilder sb = new StringBuilder("WEBVTT\n\n");
        int n = 1;
        for (TranslatedSegment cue : cues) {
            sb.append(n++).append('\n');
            sb.append(vttTime(cue.startMs())).append(" --> ").append(vttTime(cue.endMs())).append('\n');
            sb.append(cue.text() == null ? "" : cue.text().trim()).append('\n');
            sb.append('\n');
        }
        return sb.toString();
    }

    String srtTime(long millis) {
        return format(millis, ',');
    }

    String vttTime(long millis) {
        return format(millis, '.');
    }

    private String format(long millis, char msSeparator) {
        long ms = Math.max(0, millis);
        long hours = ms / 3_600_000L;
        ms %= 3_600_000L;
        long minutes = ms / 60_000L;
        ms %= 60_000L;
        long seconds = ms / 1000L;
        long fraction = ms % 1000L;
        return String.format("%02d:%02d:%02d%c%03d", hours, minutes, seconds, msSeparator, fraction);
    }
}
