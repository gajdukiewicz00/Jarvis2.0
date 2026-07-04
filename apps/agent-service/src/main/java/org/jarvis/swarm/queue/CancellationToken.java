package org.jarvis.swarm.queue;

import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation signal handed to a running task. */
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
            throw new TaskCancelledException();
        }
    }
}
