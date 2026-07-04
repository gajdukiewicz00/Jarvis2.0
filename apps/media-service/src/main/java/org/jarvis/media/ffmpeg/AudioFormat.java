package org.jarvis.media.ffmpeg;

/** Supported lossless extraction formats for the main voice track. */
public enum AudioFormat {
    WAV("wav", "pcm_s16le", "audio/wav"),
    FLAC("flac", "flac", "audio/flac");

    private final String extension;
    private final String codec;
    private final String contentType;

    AudioFormat(String extension, String codec, String contentType) {
        this.extension = extension;
        this.codec = codec;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String codec() {
        return codec;
    }

    public String contentType() {
        return contentType;
    }

    public static AudioFormat fromText(String value) {
        if (value == null || value.isBlank()) {
            return WAV;
        }
        return switch (value.trim().toLowerCase()) {
            case "flac" -> FLAC;
            case "wav" -> WAV;
            default -> throw new IllegalArgumentException("Unsupported audio format: " + value);
        };
    }
}
