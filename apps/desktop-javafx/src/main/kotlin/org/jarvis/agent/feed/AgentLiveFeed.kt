package org.jarvis.agent.feed

import org.jarvis.commands.agent.AgentEvent
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 6 — bounded in-memory feed of {@link AgentEvent}s with synchronous
 * subscriber notifications.
 *
 * <p>Pass 1 keeps the feed local: a ring buffer (default 500) plus a list
 * of subscribers fed from the same emit call. Phase 8 will fan out to
 * Kafka {@code jarvis.desktop.activity.events} and persist to Postgres so
 * the desktop UI panel and audit projector consume the same source.</p>
 */
class AgentLiveFeed(
    private val capacity: Int = 500
) {
    private val log = LoggerFactory.getLogger(AgentLiveFeed::class.java)
    private val buffer = ConcurrentLinkedDeque<AgentEvent>()
    private val subscribers = CopyOnWriteArrayList<(AgentEvent) -> Unit>()
    private val emittedCount = AtomicLong(0)
    private val droppedCount = AtomicLong(0)

    fun emit(event: AgentEvent) {
        buffer.addLast(event)
        emittedCount.incrementAndGet()
        while (buffer.size > capacity) {
            buffer.pollFirst()?.also { droppedCount.incrementAndGet() }
        }
        for (sub in subscribers) {
            try {
                sub(event)
            } catch (ex: Exception) {
                log.warn(
                    "live feed subscriber threw on event {} — continuing: {}",
                    event.eventId, ex.message
                )
            }
        }
    }

    fun recent(limit: Int = 50): List<AgentEvent> {
        val list = buffer.toList()
        return if (list.size <= limit) list else list.subList(list.size - limit, list.size)
    }

    fun subscribe(listener: (AgentEvent) -> Unit) {
        subscribers.add(listener)
    }

    fun size(): Int = buffer.size
    fun emittedCount(): Long = emittedCount.get()
    fun droppedCount(): Long = droppedCount.get()
}
