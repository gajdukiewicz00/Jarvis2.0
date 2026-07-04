package org.jarvis.agent.confirmation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DefaultConsumer
import com.rabbitmq.client.Envelope
import org.jarvis.commands.ConfirmationRequest
import org.jarvis.commands.ConfirmationResult
import org.jarvis.commands.CommandTopology
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.time.Instant

/**
 * Phase 5 — consumes {@code jarvis.commands.confirmation.request}, runs the
 * configured {@link ConfirmationStrategy}, and publishes the resulting
 * {@link ConfirmationResult} on {@code jarvis.commands.confirmation.result}.
 *
 * <p>Same plumbing pattern as {@link org.jarvis.agent.command.CommandConsumer}
 * — daemon-less native AMQP client, no Spring required. Phase 6 will swap
 * the strategy for a real JavaFX modal without touching this consumer.</p>
 */
class ConfirmationConsumer(
    private val factory: ConnectionFactory,
    private val strategy: ConfirmationStrategy,
    private val mapper: ObjectMapper = defaultMapper()
) : Closeable {

    private val log = LoggerFactory.getLogger(ConfirmationConsumer::class.java)
    private var connection: Connection? = null
    private var channel: Channel? = null

    fun start() {
        if (connection != null) {
            log.warn("ConfirmationConsumer already started")
            return
        }
        val conn = factory.newConnection("jarvis-agent-confirmation-consumer")
        val ch = conn.createChannel()
        ch.basicQos(2)
        ch.basicConsume(
            CommandTopology.QUEUE_CONFIRMATION_REQUEST,
            false,
            ConfirmationDispatcher(ch)
        )
        connection = conn
        channel = ch
        log.info(
            "ConfirmationConsumer started; subscribed to {} (strategy={})",
            CommandTopology.QUEUE_CONFIRMATION_REQUEST, strategy.javaClass.simpleName
        )
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

    private inner class ConfirmationDispatcher(channel: Channel) : DefaultConsumer(channel) {
        override fun handleDelivery(
            consumerTag: String,
            envelope: Envelope,
            properties: AMQP.BasicProperties,
            body: ByteArray
        ) {
            val deliveryTag = envelope.deliveryTag
            try {
                val req = mapper.readValue(body, ConfirmationRequest::class.java)
                log.info(
                    "[{}] confirmation request: intent={} risk={} action={} prompt='{}'",
                    req.commandId, req.intent, req.riskLevel, req.dangerousAction, req.prompt
                )

                val decision = strategy.decide(req)
                val result = ConfirmationResult.builder()
                    .commandId(req.commandId)
                    .correlationId(req.correlationId)
                    .decision(decision.decision)
                    .decidedBy(decision.decidedBy)
                    .decidedAt(Instant.now())
                    .channel("desktop")
                    .reason(decision.reason)
                    .build()

                publishResult(result)
                channel.basicAck(deliveryTag, false)
            } catch (ex: Exception) {
                log.error(
                    "[deliveryTag={}] failed to handle confirmation: {}",
                    deliveryTag, ex.message, ex
                )
                // requeue=false → DLX
                channel.basicNack(deliveryTag, false, false)
            }
        }

        private fun publishResult(result: ConfirmationResult) {
            val payload = mapper.writeValueAsBytes(result)
            val props = AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .deliveryMode(2)
                .headers(
                    mapOf(
                        CommandTopology.HEADER_COMMAND_ID to result.commandId,
                        CommandTopology.HEADER_CORRELATION_ID to result.correlationId
                    )
                )
                .build()
            channel.basicPublish(
                "",
                CommandTopology.QUEUE_CONFIRMATION_RESULT,
                props,
                payload
            )
            log.info(
                "[{}] confirmation decision published: {} by {} reason={}",
                result.commandId, result.decision, result.decidedBy, result.reason
            )
        }
    }

    companion object {
        fun defaultMapper(): ObjectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
}
