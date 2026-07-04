package org.jarvis.media.support;

import org.jarvis.media.config.MediaProperties;
import org.jarvis.media.job.InMemoryMediaJobStore;
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
        return new MediaProperties(
                true,
                new MediaProperties.Workspace(workspaceDir.toString(), ""),
                new MediaProperties.Executor(2, 32),
                new MediaProperties.Ffprobe("mock", "ffprobe", 30),
                new MediaProperties.Ffmpeg("mock", "ffmpeg", 600),
                new MediaProperties.Asr("mock"),
                new MediaProperties.Translation("mock"),
                new MediaProperties.Tts("mock", allowUserVoice),
                new MediaProperties.Subtitle(maxSegSeconds, minConf));
    }

    public static WorkspaceManager workspace(Path workspaceDir) {
        WorkspaceManager mgr = new WorkspaceManager(props(workspaceDir));
        mgr.init();
        return mgr;
    }

    /** A MediaJobService whose executor runs work inline (jobs reach terminal state synchronously). */
    public static MediaJobService syncJobService(MediaJobStore store) {
        return new MediaJobService(store, new SameThreadExecutorService(), Clock.systemUTC());
    }

    public static MediaJobStore store() {
        return new InMemoryMediaJobStore();
    }
}
