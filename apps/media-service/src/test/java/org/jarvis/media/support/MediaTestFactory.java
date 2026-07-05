package org.jarvis.media.support;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jarvis.media.config.MediaProperties;
import org.jarvis.media.job.InMemoryMediaJobStore;
import org.jarvis.media.job.MediaJobMetrics;
import org.jarvis.media.job.MediaJobService;
import org.jarvis.media.job.MediaJobStore;
import org.jarvis.media.workspace.WorkspaceManager;

import java.nio.file.Path;
import java.time.Clock;

/** Builders for deterministic media-service unit tests. */
public final class MediaTestFactory {

    private MediaTestFactory() {
    }

    public static MediaProperties props(Path workspaceDir) {
        return props(workspaceDir, false, 7, 0.5);
    }

    public static MediaProperties props(Path workspaceDir, boolean allowUserVoice, int maxSegSeconds, double minConf) {
        return props(workspaceDir, 24, allowUserVoice, maxSegSeconds, minConf);
    }

    /** Overload allowing tests to control the workspace-artifact TTL (see {@code WorkspaceCleanupService}). */
    public static MediaProperties props(Path workspaceDir, long artifactTtlHours,
                                        boolean allowUserVoice, int maxSegSeconds, double minConf) {
        return new MediaProperties(
                true,
                new MediaProperties.Workspace(workspaceDir.toString(), "", artifactTtlHours),
                new MediaProperties.Executor(2, 32),
                new MediaProperties.Ffprobe("mock", "ffprobe", 30),
                new MediaProperties.Ffmpeg("mock", "ffmpeg", 600),
                new MediaProperties.Asr("mock", "whisper-cli", "", 120),
                new MediaProperties.Translation("mock", "http://llm-service:8091"),
                new MediaProperties.Tts("mock", allowUserVoice, "piper", "", 60),
                new MediaProperties.Subtitle(maxSegSeconds, minConf));
    }

    public static WorkspaceManager workspace(Path workspaceDir) {
        WorkspaceManager mgr = new WorkspaceManager(props(workspaceDir));
        mgr.init();
        return mgr;
    }

    /** A MediaJobService whose executor runs work inline (jobs reach terminal state synchronously). */
    public static MediaJobService syncJobService(MediaJobStore store) {
        return new MediaJobService(store, new SameThreadExecutorService(), Clock.systemUTC(), metrics());
    }

    public static MediaJobStore store() {
        return new InMemoryMediaJobStore();
    }

    public static MediaJobMetrics metrics() {
        return new MediaJobMetrics(new SimpleMeterRegistry());
    }
}
