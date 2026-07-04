package org.jarvis.media.job;

/** The kind of media work an async job performs. (Probing is synchronous, not a job.) */
public enum JobType {
    EXTRACT_AUDIO,
    TRANSCRIBE,
    RUSSIAN_SUBTITLES,
    RUSSIAN_DUB_AUDIO,
    MUX
}
