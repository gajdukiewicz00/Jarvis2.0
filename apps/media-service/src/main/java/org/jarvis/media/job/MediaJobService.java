package org.jarvis.media.job;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.media.config.AsyncConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.UnaryOperator;

/**
 * Lifecycle owner for media jobs: create → submit (async) → run → terminal state.
 *
 * <p>HTTP threads never block on media work — {@link #submit} returns the CREATED
 * job immediately and the work runs on the media executor. Cancellation is both
 * cooperative (a {@link CancellationToken} the step checks) and interrupt-based
 * (the worker {@link Future} is interrupted), so a job stops promptly and ends in
 * a clean CANCELLED state.</p>
 */
@Slf4j
@Service
public class MediaJobService {

    private final MediaJobStore store;
    private final ExecutorService executor;
    private final Clock clock;

    private final ConcurrentHashMap<String, CancellationToken> tokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Future<?>> futures = new ConcurrentHashMap<>();

    public MediaJobService(MediaJobStore store,
                           @Qualifier(AsyncConfig.MEDIA_JOB_EXECUTOR) ExecutorService executor,
                           Clock clock) {
        this.store = store;
        this.executor = executor;
        this.clock = clock;
    }

    /** Create a job and schedule its work. Returns immediately with the CREATED job. */
    public MediaJob submit(JobType type, String userId, String inputFile, MediaWork work) {
        String id = UUID.randomUUID().toString();
        MediaJob job = MediaJob.created(id, userId, type, inputFile, clock.instant());
        store.save(job);

        CancellationToken token = new CancellationToken();
        tokens.put(id, token);
        try {
            Future<?> future = executor.submit(() -> runJob(id, work, token));
            futures.put(id, future);
        } catch (RejectedExecutionException rejected) {
            tokens.remove(id);
            store.save(job.failed("media executor saturated; retry later", clock.instant()));
            throw rejected;
        }
        return job;
    }

    public MediaJob getJob(String id, String userId) {
        return store.findById(id)
                .filter(j -> ownedBy(j, userId))
                .orElseThrow(() -> new JobNotFoundException(id));
    }

    public List<MediaJob> listJobs(String userId) {
        return store.findByUser(userId);
    }

    /**
     * Request cancellation. Signals the token, interrupts the worker, and marks the
     * job CANCELLED if it has not already reached a terminal state.
     *
     * @return true if a non-terminal job was cancelled, false if it was already terminal
     */
    public boolean cancel(String id, String userId) {
        MediaJob job = getJob(id, userId);
        if (job.status().isTerminal()) {
            return false;
        }
        CancellationToken token = tokens.get(id);
        if (token != null) {
            token.cancel();
        }
        Future<?> future = futures.get(id);
        if (future != null) {
            future.cancel(true);
        }
        store.findById(id).ifPresent(latest -> {
            if (!latest.status().isTerminal()) {
                store.save(latest.cancelled(clock.instant()));
            }
        });
        log.info("Media job {} cancelled by user", id);
        return true;
    }

    private void runJob(String id, MediaWork work, CancellationToken token) {
        try {
            if (token.isCancelled()) {
                finishCancelled(id);
                return;
            }
            transition(id, j -> j.running(clock.instant()));
            JobOutcome outcome = work.run(token);
            if (token.isCancelled()) {
                finishCancelled(id);
                return;
            }
            transition(id, j -> j.completed(outcome.artifacts(), outcome.details(), clock.instant()));
        } catch (JobCancelledException cancelled) {
            finishCancelled(id);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finishCancelled(id);
        } catch (Exception e) {
            log.warn("Media job {} failed: {}", id, e.toString());
            transition(id, j -> j.failed(safeMessage(e), clock.instant()));
        } finally {
            futures.remove(id);
            tokens.remove(id);
        }
    }

    private void finishCancelled(String id) {
        transition(id, j -> j.status().isTerminal() ? j : j.cancelled(clock.instant()));
    }

    private void transition(String id, UnaryOperator<MediaJob> fn) {
        store.findById(id).ifPresent(current -> store.save(fn.apply(current)));
    }

    private boolean ownedBy(MediaJob job, String userId) {
        return userId != null && userId.equals(job.userId());
    }

    /** Never surface stack traces or internal paths to clients. */
    private String safeMessage(Exception e) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg;
    }
}
