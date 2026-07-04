package org.jarvis.media.job;

/**
 * The unit of work a job runs on the media executor. Receives a
 * {@link CancellationToken} so cooperative cancellation is possible.
 */
@FunctionalInterface
public interface MediaWork {
    JobOutcome run(CancellationToken token) throws Exception;
}
