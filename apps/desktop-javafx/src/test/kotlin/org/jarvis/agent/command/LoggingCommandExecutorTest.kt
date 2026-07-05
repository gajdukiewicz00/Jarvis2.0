package org.jarvis.agent.command

import org.jarvis.commands.CommandEnvelope
import org.jarvis.commands.CommandStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LoggingCommandExecutorTest {

    @Test
    fun `execute always succeeds and echoes the intent in the output map`() {
        val envelope = CommandEnvelope.builder()
            .commandId("cmd-1")
            .correlationId("corr-1")
            .intent("pc.window.focus")
            .build()

        val result = LoggingCommandExecutor().execute(envelope)

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertEquals("cmd-1", result.commandId)
        assertEquals("corr-1", result.correlationId)
        assertEquals("pc.window.focus", result.output["intent"])
        assertEquals("logging-stub", result.output["executor"])
    }
}
