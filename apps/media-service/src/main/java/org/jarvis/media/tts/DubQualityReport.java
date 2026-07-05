package org.jarvis.media.tts;

import java.util.List;

/**
 * Dubbing quality summary.
 *
 * @param segmentCount        total segments processed
 * @param missingTts          segments that produced no audio
 * @param durationMismatches  segments whose synthetic duration differs materially from the cue duration
 * @param overlappingSpeakers segments that overlap an adjacent segment with a different speaker
 * @param tooLongSegments     segments whose cue window itself exceeds the configured max segment length
 * @param lowConfidenceSegments segments whose source ASR confidence is below the configured threshold
 * @param badSyncRisk         true when accumulated drift (or a residual overrun past the maximum
 *                            speed-up) suggests the dub may desync from the video
 * @param notes               human-readable detail lines
 */
public record DubQualityReport(
        int segmentCount,
        int missingTts,
        int durationMismatches,
        int overlappingSpeakers,
        int tooLongSegments,
        int lowConfidenceSegments,
        boolean badSyncRisk,
        List<String> notes) {

    public DubQualityReport {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
