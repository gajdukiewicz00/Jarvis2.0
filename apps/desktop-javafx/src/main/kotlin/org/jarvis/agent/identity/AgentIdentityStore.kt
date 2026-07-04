package org.jarvis.agent.identity

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.jarvis.commands.agent.AgentIdentity
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/**
 * Phase 6 — owns the agent's persistent identity.
 *
 * <p>On first run, generates a fresh {@link AgentIdentity} and writes it
 * atomically to {@code ~/.jarvis/agent/identity.json}. On subsequent runs
 * the file is read and reused so the backend sees the same {@code agentId}
 * across restarts.</p>
 */
class AgentIdentityStore(
    private val storeDir: Path = defaultStoreDir(),
    private val agentVersion: String = "phase-6",
    private val mapper: ObjectMapper = defaultMapper()
) {
    private val log = LoggerFactory.getLogger(AgentIdentityStore::class.java)
    private val identityFile: Path = storeDir.resolve("identity.json")

    fun loadOrCreate(): AgentIdentity {
        Files.createDirectories(storeDir)
        if (Files.exists(identityFile)) {
            return runCatching {
                val identity = mapper.readValue(identityFile.toFile(), AgentIdentity::class.java)
                log.info("loaded agent identity: agentId={} hostId={}", identity.agentId, identity.hostId)
                refreshVolatileFields(identity)
            }.getOrElse {
                log.warn("identity.json corrupt — regenerating ({})", it.message)
                generateAndStore()
            }
        }
        return generateAndStore()
    }

    private fun generateAndStore(): AgentIdentity {
        val identity = AgentIdentity.builder()
            .agentId("agent-" + UUID.randomUUID())
            .hostId("host-" + UUID.randomUUID())
            .hostname(System.getenv("HOSTNAME") ?: java.net.InetAddress.getLocalHost().hostName)
            .os(System.getProperty("os.name"))
            .osVersion(System.getProperty("os.version"))
            .agentVersion(agentVersion)
            .registeredAt(Instant.now())
            .build()
        write(identity)
        log.info("generated new agent identity: agentId={} hostId={}", identity.agentId, identity.hostId)
        return identity
    }

    private fun refreshVolatileFields(identity: AgentIdentity): AgentIdentity {
        // hostname/os may legitimately change across reboots (laptop name change, kernel bump);
        // the agentId stays stable to preserve correlation with prior heartbeats.
        identity.hostname = System.getenv("HOSTNAME") ?: java.net.InetAddress.getLocalHost().hostName
        identity.os = System.getProperty("os.name")
        identity.osVersion = System.getProperty("os.version")
        identity.agentVersion = agentVersion
        write(identity)
        return identity
    }

    private fun write(identity: AgentIdentity) {
        val tmp = Files.createTempFile(storeDir, "identity-", ".tmp")
        Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(identity))
        Files.move(tmp, identityFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        fun defaultStoreDir(): Path {
            val jarvisHome = System.getenv("JARVIS_HOME")?.let { Path.of(it) }
                ?: Path.of(System.getProperty("user.home"), ".jarvis")
            return jarvisHome.resolve("agent")
        }

        fun defaultMapper(): ObjectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
}
