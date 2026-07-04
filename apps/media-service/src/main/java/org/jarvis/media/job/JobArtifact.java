package org.jarvis.media.job;

/**
 * A file produced by a media job. {@code path} is always inside the workspace.
 *
 * @param kind        logical kind ("audio", "subtitle-srt", "subtitle-vtt", "dub-audio", "video")
 * @param path        absolute workspace path of the artifact
 * @param contentType MIME-ish hint ("audio/wav", "text/vtt", ...)
 * @param sizeBytes   size in bytes (0 when simulated in mock mode)
 * @param note        optional human note
 */
public record JobArtifact(String kind, String path, String contentType, long sizeBytes, String note) {

    public static JobArtifact of(String kind, String path, String contentType, long sizeBytes) {
        return new JobArtifact(kind, path, contentType, sizeBytes, null);
    }
}
