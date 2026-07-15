package org.jarvis.agent.confirmation

import com.fasterxml.jackson.databind.ObjectMapper
import com.rabbitmq.client.AMQP
import org.jarvis.agent.rabbit.FakeRabbit
import org.jarvis.commands.ConfirmationDecision
import org.jarvis.commands.ConfirmationRequest
import org.jarvis.commands.ConfirmationResult
import org.jarvis.commands.CommandTopology
import org.jarvis.commands.RiskLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Branch coverage for [ConfirmationConsumer] and its inner
 * {@code ConfirmationDispatcher}, exercised without a live broker via
 * [FakeRabbit]. The confirmation strategy is a plain lambda
 * ({@code ConfirmationStrategy} is a fun interface), so no interactive prompt
 * is involved.
 */
class ConfirmationConsumerHandleDeliveryTest {

    private val mapper: ObjectMapper = ConfirmationConsumer.defaultMapper()

    private fun request(
        commandId: String = "cmd-1",
        correlationId: String = "corr-1",
    ): ConfirmationRequest = ConfirmationRequest.builder()
        .commandId(commandId)
        .correlationId(correlationId)
        .intent("pc.shutdown")
        .riskLevel(RiskLevel.HIGH)
        .prompt("shut down?")
        .build()

    private fun bytesOf(req: ConfirmationRequest): ByteArray = mapper.writeValueAsBytes(req)

    private val props: AMQP.BasicProperties = AMQP.BasicProperties.Builder().build()

    private fun startWith(rabbit: FakeRabbit, strategy: ConfirmationStrategy): ConfirmationConsumer {
        val consumer = ConfirmationConsumer(rabbit.factory, strategy, mapper)
        consumer.start()
        return consumer
    }

    @Test
    fun `start subscribes with prefetch and captures the dispatcher`() {
        val rabbit = FakeRabbit()
        startWith(rabbit) { ConfirmationStrategy.Decision(ConfirmationDecision.DENIED, "auto-deny") }

        assertNotNull(rabbit.consumer)
        assertEquals(2, rabbit.qos)
        assertEquals(1, rabbit.channelsCreated)
    }

    @Test
    fun `second start is a no-op and does not open another channel`() {
        val rabbit = FakeRabbit()
        val consumer = startWith(rabbit) {
            ConfirmationStrategy.Decision(ConfirmationDecision.DENIED, "auto-deny")
        }

        consumer.start()

        assertEquals(1, rabbit.channelsCreated)
    }

    @Test
    fun `decision is published on the result queue and the request is acked`() {
        val rabbit = FakeRabbit()
        val strategy = ConfirmationStrategy { req ->
            ConfirmationStrategy.Decision(ConfirmationDecision.APPROVED, req.userId ?: "owner", "looks fine")
        }
        startWith(rabbit, strategy)

        rabbit.consumer!!.handleDelivery("ct", rabbit.envelope(21L), props, bytesOf(request()))

        assertEquals(listOf(21L), rabbit.acks)
        assertTrue(rabbit.nacks.isEmpty())

        val publish = rabbit.publishes.single()
        assertEquals("", publish.exchange)
        assertEquals(CommandTopology.QUEUE_CONFIRMATION_RESULT, publish.routingKey)
        assertEquals(2, publish.props?.deliveryMode)
        assertEquals("cmd-1", publish.props?.headers?.get(CommandTopology.HEADER_COMMAND_ID))

        val result = mapper.readValue(publish.body, ConfirmationResult::class.java)
        assertEquals(ConfirmationDecision.APPROVED, result.decision)
        assertEquals("owner", result.decidedBy)
        assertEquals("desktop", result.channel)
        assertEquals("looks fine", result.reason)
        assertNotNull(result.decidedAt)
    }

    @Test
    fun `denied decision carries the strategy reason through to the result`() {
        val rabbit = FakeRabbit()
        startWith(rabbit) {
            ConfirmationStrategy.Decision(ConfirmationDecision.DENIED, "auto-deny", "policy denies HIGH risk")
        }

        rabbit.consumer!!.handleDelivery("ct", rabbit.envelope(22L), props, bytesOf(request()))

        val result = mapper.readValue(rabbit.publishes.single().body, ConfirmationResult::class.java)
        assertEquals(ConfirmationDecision.DENIED, result.decision)
        assertEquals("policy denies HIGH risk", result.reason)
    }

    @Test
    fun `null reason from strategy is omitted from the published result`() {
        val rabbit = FakeRabbit()
        startWith(rabbit) {
            ConfirmationStrategy.Decision(ConfirmationDecision.APPROVED, "operator", null)
        }

        rabbit.consumer!!.handleDelivery("ct", rabbit.envelope(23L), props, bytesOf(request()))

        val result = mapper.readValue(rabbit.publishes.single().body, ConfirmationResult::class.java)
        assertNull(result.reason)
    }

    @Test
    fun `unparseable body nacks to DLX without requeue`() {
        val rabbit = FakeRabbit()
        startWith(rabbit) {
            ConfirmationStrategy.Decision(ConfirmationDecision.DENIED, "auto-deny")
        }

        rabbit.consumer!!.handleDelivery("ct", rabbit.envelope(24L), props, "{bad".toByteArray())

        assertTrue(rabbit.acks.isEmpty())
        assertTrue(rabbit.publishes.isEmpty())
        val (tag, multiple, requeue) = rabbit.nacks.single()
        assertEquals(24L, tag)
        assertFalse(multiple)
        assertFalse(requeue)
    }

    @Test
    fun `strategy exception is caught and nacked without publishing`() {
        val rabbit = FakeRabbit()
        startWith(rabbit) { throw IllegalStateException("strategy blew up") }

        rabbit.consumer!!.handleDelivery("ct", rabbit.envelope(25L), props, bytesOf(request()))

        assertTrue(rabbit.acks.isEmpty())
        assertTrue(rabbit.publishes.isEmpty())
        assertEquals(1, rabbit.nacks.size)
        assertFalse(rabbit.nacks.single().third, "requeue=false → DLX")
    }

    @Test
    fun `close swallows channel and connection close failures`() {
        val rabbit = FakeRabbit(failChannelClose = true, failConnectionClose = true)
        val consumer = startWith(rabbit) {
            ConfirmationStrategy.Decision(ConfirmationDecision.DENIED, "auto-deny")
        }

        consumer.close()
    }

    @Test
    fun `close tears down channel and connection on the happy path`() {
        val rabbit = FakeRabbit()
        val consumer = startWith(rabbit) {
            ConfirmationStrategy.Decision(ConfirmationDecision.DENIED, "auto-deny")
        }

        consumer.close()

        assertTrue(rabbit.channelClosed)
        assertTrue(rabbit.connectionClosed)
    }
}
