package org.jarvis.agent.command

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 4 — in-memory deduplication store for inbound commands.
 *
 * <p>Caffeine cache with a TTL (default 1h) keyed by {@code commandId}. The
 * consumer calls {@link #seenOrMark} before executing; if the id is already
 * present, the command is treated as a duplicate and acked without
 * re-executing. The TTL is long enough to cover broker requeue + agent
 * restart races, short enough to keep memory bounded.</p>
 */
class CommandIdempotencyStore(
    ttl: Duration = Duration.ofHours(1),
    maxSize: Long = 100_000
) {
    private val cache: Cache<String, Long> = Caffeine.newBuilder()
        .expireAfterWrite(ttl)
        .maximumSize(maxSize)
        .build()
    private val duplicates = AtomicLong(0)

    /**
     * @return true if the [commandId] was NOT seen before (caller must execute)
     */
    fun seenOrMark(commandId: String): Boolean {
        val now = System.currentTimeMillis()
        val previous = cache.asMap().putIfAbsent(commandId, now)
        if (previous != null) {
            duplicates.incrementAndGet()
            return false
        }
        return true
    }

    fun duplicateCount(): Long = duplicates.get()
    fun size(): Long = cache.estimatedSize()
}
