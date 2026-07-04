package org.jarvis.media.job;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation signal handed to each running job step. Long steps
 * (per-segment transcription, dubbing) must call {@link #throwIfCancelled()}
 * between units of work so a cancel request stops the job promptly and safely.
 */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new JobCancelledException();
        }
    }
}
