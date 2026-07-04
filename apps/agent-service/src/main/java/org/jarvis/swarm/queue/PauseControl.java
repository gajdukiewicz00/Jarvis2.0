package org.jarvis.swarm.queue;

/**
 * Cooperative pause gate for one running task. The worker blocks in
 * {@link #awaitWhilePaused} at each checkpoint while paused; {@link #resume} releases it.
 * Cancellation always wins over a pause so a paused task can still be cancelled.
 */
public final class PauseControl {

    private final Object lock = new Object();
    private volatile boolean paused = false;

    public void pause() {
        paused = true;
    }

    public void resume() {
        synchronized (lock) {
            paused = false;
            lock.notifyAll();
        }
    }

    public boolean isPaused() {
        return paused;
    }

    /** Block while paused, waking on resume or cancellation. */
    public void awaitWhilePaused(CancellationToken token) {
        synchronized (lock) {
            while (paused && !token.isCancelled()) {
                try {
                    lock.wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        token.throwIfCancelled();
    }
}
