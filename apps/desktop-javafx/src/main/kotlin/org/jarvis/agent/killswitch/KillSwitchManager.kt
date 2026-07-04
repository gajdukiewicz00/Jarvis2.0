package org.jarvis.agent.killswitch

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.agent.identity.AgentIdentityStore
import org.jarvis.commands.agent.AgentEvent
import org.jarvis.commands.agent.KillSwitchState
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 6 — central authority for the agent's emergency kill switch.
 *
 * <p>The state is held atomically in memory and mirrored to
 * {@code ~/.jarvis/agent/killswitch.json} so it survives a process
 * restart — an operator who hits the kill switch wants the agent to stay
 * dead until they explicitly disengage.</p>
 *
 * <p>Every transition produces an {@link AgentEvent} on the supplied
 * {@link AgentLiveFeed}; Phase 8 will persist these via the Kafka audit
 * backbone.</p>
 *
 * <p>Consumers MUST call {@link #ensureClear} before performing privileged
 * work; the call throws {@link KillSwitchEngagedException} when the
 * switch is engaged, causing the message to land in DLQ rather than
 * silently disappear.</p>
 */
class KillSwitchManager(
    private val agentId: String,
    private val feed: AgentLiveFeed,
    private val storeDir: Path = AgentIdentityStore.defaultStoreDir(),
    private val mapper: ObjectMapper = defaultMapper()
) {
    private val log = LoggerFactory.getLogger(KillSwitchManager::class.java)
    private val stateFile: Path = storeDir.resolve("killswitch.json")
    private val state = AtomicReference<KillSwitchState>(KillSwitchState.disengaged())

    fun load() {
        if (!Files.exists(stateFile)) return
        runCatching {
            val loaded = mapper.readValue(stateFile.toFile(), KillSwitchState::class.java)
            state.set(loaded)
            if (loaded.isEngaged) {
                log.warn("kill switch loaded as ENGAGED (engagedAt={} reason='{}')",
                    loaded.engagedAt, loaded.reason)
            } else {
                log.info("kill switch loaded as disengaged")
            }
        }.onFailure {
            log.warn("killswitch.json corrupt — defaulting to disengaged ({})", it.message)
            state.set(KillSwitchState.disengaged())
        }
    }

    fun current(): KillSwitchState = state.get()
    fun isEngaged(): Boolean = state.get().isEngaged

    fun engage(by: String, reason: String): KillSwitchState {
        val previous = state.get()
        if (previous.isEngaged) {
            log.info("kill switch already engaged by {} — ignoring engage request from {}",
                previous.engagedBy, by)
            return previous
        }
        val next = KillSwitchState.engaged(by, reason)
        state.set(next)
        persist(next)
        feed.emit(AgentEvent.warn(
            agentId,
            AgentEvent.Type.KILL_SWITCH_ENGAGED,
            "kill switch engaged by $by — $reason",
            mapOf(
                "engagedBy" to by,
                "reason" to reason,
                "engagedAt" to next.engagedAt.toString()
            )
        ))
        log.warn("KILL SWITCH ENGAGED by {} — reason: {}", by, reason)
        return next
    }

    fun disengage(by: String): KillSwitchState {
        val previous = state.get()
        if (!previous.isEngaged) {
            log.debug("kill switch already disengaged — ignoring disengage from {}", by)
            return previous
        }
        val next = KillSwitchState.builder()
            .engaged(false)
            .engagedAt(previous.engagedAt)
            .disengagedAt(java.time.Instant.now())
            .engagedBy(previous.engagedBy)
            .reason(previous.reason)
            .build()
        state.set(next)
        persist(next)
        feed.emit(AgentEvent.info(
            agentId,
            AgentEvent.Type.KILL_SWITCH_DISENGAGED,
            "kill switch disengaged by $by",
            mapOf(
                "disengagedBy" to by,
                "previousEngagedBy" to (previous.engagedBy ?: ""),
                "engagedDurationSec" to java.time.Duration.between(
                    previous.engagedAt ?: next.disengagedAt, next.disengagedAt).seconds
            )
        ))
        log.warn("KILL SWITCH DISENGAGED by {}", by)
        return next
    }

    /**
     * Throws {@link KillSwitchEngagedException} if the switch is currently
     * engaged. Call this from every consumer before doing privileged work.
     */
    fun ensureClear() {
        val s = state.get()
        if (s.isEngaged) {
            throw KillSwitchEngagedException(
                "kill switch ENGAGED by ${s.engagedBy} at ${s.engagedAt} — '${s.reason}'"
            )
        }
    }

    private fun persist(next: KillSwitchState) {
        runCatching {
            Files.createDirectories(storeDir)
            val tmp = Files.createTempFile(storeDir, "killswitch-", ".tmp")
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(next))
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            log.error("failed to persist killswitch state: {}", it.message, it)
        }
    }

    companion object {
        fun defaultMapper(): ObjectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
}
