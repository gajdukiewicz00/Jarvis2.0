package org.jarvis.agent.command

import com.fasterxml.jackson.databind.ObjectMapper
import com.rabbitmq.client.AMQP
import org.jarvis.agent.rabbit.FakeRabbit
import org.jarvis.commands.CommandEnvelope
import org.jarvis.commands.CommandResult
import org.jarvis.commands.CommandStatus
import org.jarvis.commands.CommandTopology
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Branch coverage for [CommandConsumer] and its inner {@code CommandDispatcher}
 * message-handling logic, exercised without a live broker. [FakeRabbit] captures
 * the registered consumer via {@code start()}, then each test drives
 * {@code handleDelivery(...)} directly and asserts on the recorded broker calls.
 */
class CommandConsumerHandleDeliveryTest {

    private val mapper: ObjectMapper = CommandConsumer.defaultMapper()

    private fun envelope(
        commandId: String = "cmd-1",
        correlationId: String = "corr-1",
        intent: String = "pc.window.focus",
        expiresAt: Instant? = null,
    ): CommandEnvelope = CommandEnvelope.builder()
        .commandId(commandId)
        .correlationId(correlationId)
        .intent(intent)
        .status(CommandStatus.QUEUED)
        .expiresAt(expiresAt)
        .build()

    private fun bytesOf(env: CommandEnvelope): ByteArray = mapper.writeValueAsBytes(env)

    private val props: AMQP.BasicProperties = AMQP.BasicProperties.Builder().build()

    /** Start a consumer against the fake broker and return the captured consumer. */
    private fun startWith(
        rabbit: FakeRabbit,
        executor: CommandExecutor = LoggingCommandExecutor(),
        store: CommandIdempotencyStore = CommandIdempotencyStore(),
    ): CommandConsumer {
        val consumer = CommandConsumer(rabbit.factory, executor, store, mapper)
        consumer.start()
        return consumer
    }

    @Test
    fun `start subscribes with prefetch and captures the dispatcher`() {
        val rabbit = FakeRabbit()
        startWith(rabbit)

        assertNotNull(rabbit.consumer, "dispatcher should be registered via basicConsume")
        assertEquals(4, rabbit.qos)
        assertEquals(1, rabbit.channelsCreated)
    }

    @Test
    fun `second start is a no-op and does not open another channel`() {
        val rabbit = FakeRabbit()
        val consumer = startWith(rabbit)

        consumer.start()

        assertEquals(1, rabbit.channelsCreated, "already-started guard should short-circuit")
    }

    @Test
    fun `happy path executes publishes SUCCESS and acks`() {
        val rabbit = FakeRabbit()
        startWith(rabbit)

        rabbit.consumer!!.handleDelivery("ct", rabbit.envelope(10L), props, bytesOf(envelope()))

        assertEquals(listOf(10L), rabbit.acks)
        assertTrue(rabbit.nacks.isEmpty())
        assertEquals(1, rabbit.publishes.size)

        val publish = rabbit.publishes.single()
        assertEquals("", publish.exchange)
        assertEquals(CommandTopology.QUEUE_AGENT_RESULT, publish.routingKey)
        assertEquals("application/json", publish.props?.contentType)
        assertEquals(2, publish.props?.deliveryMode)
        assertEquals("cmd-1", publish.props?.headers?.get(CommandTopology.HEADER_COMMAND_ID))

        val result = mapper.readValue(publish.body, CommandResult::class.java)
        assertEquals(CommandStatus.SUCCESS, result.status)
        assertEquals("cmd-1", result.commandId)
        assertEquals("corr-1", result.correlationId)
    }

    @Test
    fun `duplicate command is acked without executing or publishing`() {
        val rabbit = FakeRabbit()
        val store = CommandIdempotencyStore()
        // Pre-mark the id so the consumer sees it as a duplicate.
        assertTrue(store.seenOrMark("cmd-dup"))
        var executed = false
        val executor = CommandExecutor { env ->
            executed = true
            CommandResult.success(env.commandId, env.correlationId, emptyMap(), 0)
        }
        startWith(rabbit, executor, store)

        rabbit.consumer!!.handleDelivery(
            "ct", rabbit.envelope(11L), props, bytesOf(envelope(commandId = "cmd-dup")),
        )

        assertEquals(listOf(11L), rabbit.acks)
        assertTrue(rabbit.publishes.isEmpty(), "duplicate must not publish a result")
        assertFalse(executed, "duplicate must not reach the executor")
    }

    @Test
    fun `expired command publishes EXPIRED result and acks without executing`() {
        val rabbit = FakeRabbit()
        var executed = false
        val executor = CommandExecutor { env ->
            executed = true
            CommandResult.success(env.commandId, env.correlationId, emptyMap(), 0)
        }
        startWith(rabbit, executor)

        val expired = envelope(commandId = "cmd-exp", expiresAt = Instant.now().minusSeconds(60))
        rabbit.consumer!!.handleDelivery("ct", rabbit.envelope(12L), props, bytesOf(expired))

        assertEquals(listOf(12L), rabbit.acks)
        assertFalse(executed, "expired command must not reach the executor")
        val result = mapper.readValue(rabbit.publishes.single().body, CommandResult::class.java)
        assertEquals(CommandStatus.EXPIRED, result.status)
        assertEquals("cmd-exp", result.commandId)
    }

    @Test
    fun `executor exception is caught and published as FAILED then acked`() {
        val rabbit = FakeRabbit()
        val executor = CommandExecutor { throw IllegalStateException("kaboom") }
        startWith(rabbit, executor)

        rabbit.consumer!!.handleDelivery(
            "ct", rabbit.envelope(13L), props, bytesOf(envelope(commandId = "cmd-fail")),
        )

        assertEquals(listOf(13L), rabbit.acks, "a handled executor failure still acks")
        assertTrue(rabbit.nacks.isEmpty())
        val result = mapper.readValue(rabbit.publishes.single().body, CommandResult::class.java)
        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(result.errorReason!!.contains("IllegalStateException"))
        assertTrue(result.errorReason!!.contains("kaboom"))
    }

    @Test
    fun `unparseable body nacks to DLX without requeue and without publishing`() {
        val rabbit = FakeRabbit()
        startWith(rabbit)

        rabbit.consumer!!.handleDelivery("ct", rabbit.envelope(14L), props, "not-json".toByteArray())

        assertTrue(rabbit.acks.isEmpty())
        assertTrue(rabbit.publishes.isEmpty())
        assertEquals(1, rabbit.nacks.size)
        val (tag, multiple, requeue) = rabbit.nacks.single()
        assertEquals(14L, tag)
        assertFalse(multiple)
        assertFalse(requeue, "requeue=false routes the poison message to the DLX")
    }

    @Test
    fun `close swallows channel and connection close failures`() {
        val rabbit = FakeRabbit(failChannelClose = true, failConnectionClose = true)
        val consumer = startWith(rabbit)

        // Must not propagate despite both close() calls throwing.
        consumer.close()
    }

    @Test
    fun `close tears down channel and connection on the happy path`() {
        val rabbit = FakeRabbit()
        val consumer = startWith(rabbit)

        consumer.close()

        assertTrue(rabbit.channelClosed)
        assertTrue(rabbit.connectionClosed)
    }
}
