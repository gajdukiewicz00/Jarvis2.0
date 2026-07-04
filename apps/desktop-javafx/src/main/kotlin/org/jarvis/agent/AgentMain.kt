package org.jarvis.agent

import com.rabbitmq.client.ConnectionFactory
import org.jarvis.agent.audit.AuditForwarder
import org.jarvis.agent.command.CommandConsumer
import org.jarvis.agent.command.CommandIdempotencyStore
import org.jarvis.agent.command.NativeDesktopCommandExecutor
import org.jarvis.agent.confirmation.ConfirmationConsumer
import org.jarvis.agent.confirmation.ConfirmationStrategy
import org.jarvis.agent.feed.AgentLiveFeed
import org.jarvis.agent.heartbeat.HeartbeatPublisher
import org.jarvis.agent.identity.AgentIdentityStore
import org.jarvis.agent.identity.CapabilityProbe
import org.jarvis.agent.killswitch.KillSwitchManager
import org.jarvis.agent.permission.KillSwitchAwareConfirmationStrategy
import org.jarvis.agent.permission.PermissionAwareExecutor
import org.jarvis.agent.permission.PermissionGate
import org.jarvis.agent.status.StatusAggregator
import org.jarvis.commands.agent.AgentEvent
import org.jarvis.commands.agent.AgentStatus
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch

/**
 * Phase 4 — standalone entrypoint that runs the desktop-side command
 * consumer as a sidecar process.
 *
 * <p>Reads broker config from environment:</p>
 * <pre>
 *   JARVIS_AGENT_RABBITMQ_HOST       (default: localhost)
 *   JARVIS_AGENT_RABBITMQ_PORT       (default: 5672)
 *   JARVIS_AGENT_RABBITMQ_USERNAME   (default: jarvis)
 *   JARVIS_AGENT_RABBITMQ_PASSWORD   (default: jarvis)
 *   JARVIS_AGENT_RABBITMQ_VHOST      (default: jarvis)
 * </pre>
 *
 * <p>Phase 6 (Native Desktop Agent Stabilization) will integrate this loop
 * into the JavaFX shell so it runs in a single process; until then it can
 * be launched separately by the operator.</p>
 *
 * <p>Run via Maven:</p>
 * <pre>
 *   mvn -pl apps/desktop-javafx exec:java \
 *     -Dexec.mainClass=org.jarvis.agent.AgentMainKt
 * </pre>
 */
object AgentMain {
    private val log = LoggerFactory.getLogger(AgentMain::class.java)

    @JvmStatic
    fun main(args: Array<String>) {
        log.info("Jarvis Native Desktop Agent (Phase 4-6) booting")

        // Phase 6 — identity + capabilities + kill switch + live feed
        val identity = AgentIdentityStore().loadOrCreate()
        val capabilities = CapabilityProbe().detect()
        val feed = AgentLiveFeed()
        val killSwitch = KillSwitchManager(
            agentId = identity.agentId ?: "unknown",
            feed = feed
        ).also { it.load() }
        val gate = PermissionGate(killSwitch, capabilities)

        // Phase 6 — backend handshake + heartbeat
        val backendBaseUrl = System.getenv("JARVIS_AGENT_BACKEND_URL")
            ?: "https://api.jarvis.local"
        val statusAggregator = StatusAggregator(backendBaseUrl).also { it.start() }
        val heartbeat = HeartbeatPublisher(
            identity = identity,
            capabilities = capabilities,
            killSwitch = killSwitch,
            feed = feed,
            backendBaseUrl = backendBaseUrl
        )
        heartbeat.overrideStatus(AgentStatus.BOOTING)
        runCatching { heartbeat.register() }
            .onSuccess {
                feed.emit(AgentEvent.info(
                    identity.agentId,
                    AgentEvent.Type.COMMAND_EXECUTED,
                    "agent registered with backend",
                    mapOf("backend" to backendBaseUrl)
                ))
            }

        // Phase 8 — drain live-feed entries into the api-gateway audit ingest
        // endpoint so every executor action lands in jarvis.audit.events.
        val auditForwarder = AuditForwarder(
            gatewayBaseUrl = backendBaseUrl,
            agentId = identity.agentId ?: "unknown",
            feed = feed
        ).also { it.start() }

        // Phase 4 + 5 — command + confirmation consumers, wrapped with the gate
        val factory = buildConnectionFactory()
        // Phase 6 — real desktop executor (OPEN_APP, FOCUS_WINDOW, TYPE_TEXT,
        // OPEN_URL, CREATE_LOCAL_NOTE, SHOW_NOTIFICATION, GET_ACTIVE_WINDOW).
        // Dangerous intents are still gated behind the Phase-5 confirmation
        // flow and the kill-switch / capability check below.
        val baseExecutor = NativeDesktopCommandExecutor()
        val gatedExecutor = PermissionAwareExecutor(
            baseExecutor, gate, feed, identity.agentId ?: "unknown"
        )
        val commandConsumer = CommandConsumer(
            factory = factory,
            executor = gatedExecutor,
            idempotency = CommandIdempotencyStore()
        )
        commandConsumer.start()

        // Phase 5/6 — confirmation strategy. Defaults to "require human"
        // (CLI prompt that denies on EOF). Auto-approve is rejected at boot
        // unless JARVIS_AGENT_PROFILE is one of the explicit non-prod
        // profiles AND JARVIS_AGENT_ALLOW_AUTO_APPROVE=true. Misconfigured
        // prod nodes crash here on purpose — no silent fallback.
        val baseStrategy = ConfirmationStrategy.fromEnv()
        val gatedStrategy = KillSwitchAwareConfirmationStrategy(
            baseStrategy, killSwitch, feed, identity.agentId ?: "unknown"
        )
        val confirmationConsumer = ConfirmationConsumer(
            factory = factory,
            strategy = gatedStrategy
        )
        confirmationConsumer.start()

        heartbeat.overrideStatus(null)
        heartbeat.start()

        log.info(
            "Agent ready: agentId={} caps={} strategy={} backend={}",
            identity.agentId, capabilities, baseStrategy.javaClass.simpleName, backendBaseUrl
        )

        val latch = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread {
            log.info("shutdown signal received, closing agent")
            runCatching { heartbeat.close() }
                .onFailure { log.warn("heartbeat close failed: ${it.message}") }
            runCatching { statusAggregator.stop() }
            runCatching { confirmationConsumer.close() }
                .onFailure { log.warn("confirmation close failed: ${it.message}") }
            runCatching { commandConsumer.close() }
                .onFailure { log.warn("command close failed: ${it.message}") }
            runCatching { auditForwarder.close() }
                .onFailure { log.warn("audit forwarder close failed: ${it.message}") }
            latch.countDown()
        })
        latch.await()
        log.info("Jarvis agent stopped")
    }

    private fun buildConnectionFactory(): ConnectionFactory {
        val factory = ConnectionFactory()
        factory.host = System.getenv("JARVIS_AGENT_RABBITMQ_HOST") ?: "localhost"
        factory.port = System.getenv("JARVIS_AGENT_RABBITMQ_PORT")?.toIntOrNull() ?: 5672
        factory.username = System.getenv("JARVIS_AGENT_RABBITMQ_USERNAME") ?: "jarvis"
        factory.password = System.getenv("JARVIS_AGENT_RABBITMQ_PASSWORD") ?: "jarvis"
        factory.virtualHost = System.getenv("JARVIS_AGENT_RABBITMQ_VHOST") ?: "jarvis"
        factory.isAutomaticRecoveryEnabled = true
        factory.networkRecoveryInterval = 5_000
        return factory
    }
}
