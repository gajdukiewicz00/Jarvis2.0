package org.jarvis.agent.killswitch

/**
 * Phase 6 — thrown by any agent component asked to perform an action while
 * the kill switch is engaged. Consumers should catch it, NACK the message
 * (DLQ), and emit an {@code ERROR} live-feed event.
 */
class KillSwitchEngagedException(reason: String) : RuntimeException(reason)
