package org.jarvis.agent.command

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DefaultConsumer
import com.rabbitmq.client.Envelope
import org.jarvis.commands.CommandEnvelope
import org.jarvis.commands.CommandResult
import org.jarvis.commands.CommandStatus
import org.jarvis.commands.CommandTopology
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.time.Instant

/**
 * Phase 4 — desktop-side consumer for {@code jarvis.commands.agent.execute}.
 *
 * <p>Daemon-less RabbitMQ client (no Spring AMQP). Steps for every delivery:</p>
 * <ol>
 *   <li>Deserialise the {@link CommandEnvelope} from JSON.</li>
 *   <li>Idempotency: skip if {@code commandId} was seen before (ack and exit).</li>
 *   <li>Expiry check: if {@code expiresAt} has passed, publish an
 *       {@code EXPIRED} result and ack.</li>
 *   <li>Hand the envelope to the {@link CommandExecutor}.</li>
 *   <li>Publish the {@link CommandResult} to
 *       {@code jarvis.commands.agent.result}.</li>
 *   <li>Ack on success. On unexpected exception, NACK with
 *       {@code requeue=false} → message lands in {@code jarvis.dlx.commands}.</li>
 * </ol>
 */
class CommandConsumer(
    private val factory: ConnectionFactory,
    private val executor: CommandExecutor = LoggingCommandExecutor(),
    private val idempotency: CommandIdempotencyStore = CommandIdempotencyStore(),
    private val mapper: ObjectMapper = defaultMapper()
) : Closeable {

    private val log = LoggerFactory.getLogger(CommandConsumer::class.java)
    private var connection: Connection? = null
    private var channel: Channel? = null

    fun start() {
        if (connection != null) {
            log.warn("CommandConsumer already started")
            return
        }
        val conn = factory.newConnection("jarvis-agent-command-consumer")
        val ch = conn.createChannel()
        ch.basicQos(4)
        ch.basicConsume(
            CommandTopology.QUEUE_AGENT_EXECUTE,
            false,
            CommandDispatcher(ch)
        )
        connection = conn
        channel = ch
        log.info("CommandConsumer started; subscribed to ${CommandTopology.QUEUE_AGENT_EXECUTE}")
    }

    override fun close() {
        try {
            channel?.close()
        } catch (ex: Exception) {
            log.debug("channel close ignored: ${ex.message}")
        }
        try {
            connection?.close()
        } catch (ex: Exception) {
            log.debug("connection close ignored: ${ex.message}")
        }
        channel = null
        connection = null
    }

    private inner class CommandDispatcher(channel: Channel) : DefaultConsumer(channel) {
        override fun handleDelivery(
            consumerTag: String,
            envelope: Envelope,
            properties: AMQP.BasicProperties,
            body: ByteArray
        ) {
            val deliveryTag = envelope.deliveryTag
            try {
                val cmd = mapper.readValue(body, CommandEnvelope::class.java)

                // Idempotency
                if (!idempotency.seenOrMark(cmd.commandId)) {
                    log.warn("[{}] duplicate command — acking, no execution", cmd.commandId)
                    channel.basicAck(deliveryTag, false)
                    return
                }

                // Expiry
                if (cmd.isExpired(Instant.now())) {
                    log.warn(
                        "[{}] command expired at {} — publishing EXPIRED result",
                        cmd.commandId, cmd.expiresAt
                    )
                    publishResult(
                        CommandResult.expired(
                            cmd.commandId, cmd.correlationId,
                            "TTL exceeded before execution"
                        )
                    )
                    channel.basicAck(deliveryTag, false)
                    return
                }

                cmd.status = CommandStatus.EXECUTING
                val result = try {
                    executor.execute(cmd)
                } catch (ex: Exception) {
                    log.error("[{}] executor threw: {}", cmd.commandId, ex.message, ex)
                    CommandResult.failed(
                        cmd.commandId, cmd.correlationId,
                        "executor exception: ${ex.javaClass.simpleName}: ${ex.message}",
                        0
                    )
                }
                publishResult(result)
                channel.basicAck(deliveryTag, false)
            } catch (ex: Exception) {
                log.error("[deliveryTag={}] failed to process command: {}",
                    deliveryTag, ex.message, ex)
                // requeue=false routes to DLX -> jarvis.dlx.commands
                channel.basicNack(deliveryTag, false, false)
            }
        }

        private fun publishResult(result: CommandResult) {
            val payload = mapper.writeValueAsBytes(result)
            val props = AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .deliveryMode(2) // persistent
                .headers(
                    mapOf(
                        CommandTopology.HEADER_COMMAND_ID to result.commandId,
                        CommandTopology.HEADER_CORRELATION_ID to result.correlationId
                    )
                )
                .build()
            channel.basicPublish(
                "",
                CommandTopology.QUEUE_AGENT_RESULT,
                props,
                payload
            )
            log.info(
                "[{}] result published: status={} duration={}ms",
                result.commandId, result.status, result.durationMillis
            )
        }
    }

    companion object {
        fun defaultMapper(): ObjectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
}
