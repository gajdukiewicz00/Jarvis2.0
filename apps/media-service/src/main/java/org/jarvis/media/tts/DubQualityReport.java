package org.jarvis.media.tts;

import java.util.List;

/**
 * Dubbing quality summary.
 *
 * @param segmentCount        total segments processed
 * @param missingTts          segments that produced no audio
 * @param durationMismatches  segments whose synthetic duration differs materially from the cue duration
 * @param overlappingSpeakers segments that overlap an adjacent segment with a different speaker
 * @param badSyncRisk         true when accumulated drift suggests the dub may desync from the video
 * @param notes               human-readable detail lines
 */
public record DubQualityReport(
        int segmentCount,
        int missingTts,
        int durationMismatches,
        int overlappingSpeakers,
        boolean badSyncRisk,
        List<String> notes) {

    public DubQualityReport {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
