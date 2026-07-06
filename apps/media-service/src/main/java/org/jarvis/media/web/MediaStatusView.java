package org.jarvis.media.web;

import org.jarvis.media.config.MediaProperties;

/**
 * API view reporting the media service's current operating mode: whether the
 * feature flag is enabled, which {@code MediaJobStore} implementation is active, and
 * whether each media provider (ffprobe/ffmpeg/ASR/translation/TTS) is running in
 * {@code mock} or a real/native mode. Lets a UI clearly distinguish a demo/mock
 * response from one produced by a real ffmpeg/whisper/piper binary, without having
 * to infer it from job output shape.
 */
public record MediaStatusView(boolean enabled, String jobStore, Providers providers) {

    /** Configured mode string for each provider, taken verbatim from {@link MediaProperties}. */
    public record Providers(String ffprobe, String ffmpeg, String asr, String translation, String tts) {}

    public static MediaStatusView of(MediaProperties props, String jobStoreMode) {
        return new MediaStatusView(
                props.enabled(),
                jobStoreMode,
                new Providers(
                        props.ffprobe().mode(),
                        props.ffmpeg().mode(),
                        props.asr().mode(),
                        props.translation().mode(),
                        props.tts().mode()));
    }
}
