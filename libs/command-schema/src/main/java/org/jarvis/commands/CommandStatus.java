package org.jarvis.commands;

/**
 * Phase 4 — lifecycle states for a command flowing through the pipeline.
 *
 * <pre>
 *  CREATED -> QUEUED -> EXECUTING -> SUCCESS
 *                                 -> FAILED
 *                                 -> EXPIRED
 *                                 -> REJECTED   (Phase 5 confirmation denied)
 *                  -> EXPIRED                   (TTL hit on the queue)
 * </pre>
 */
public enum CommandStatus {
    CREATED,
    QUEUED,
    AWAITING_CONFIRMATION,
    EXECUTING,
    SUCCESS,
    FAILED,
    EXPIRED,
    REJECTED
}
