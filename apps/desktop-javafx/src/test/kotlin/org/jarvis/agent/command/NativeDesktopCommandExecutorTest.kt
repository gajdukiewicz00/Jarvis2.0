package org.jarvis.agent.command

import org.jarvis.commands.CommandEnvelope
import org.jarvis.commands.CommandStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID

class NativeDesktopCommandExecutorTest {

    private val recorded = mutableListOf<String>()
    private val actions = object : DesktopActions {
        override fun openApp(app: String, args: List<String>): DesktopActions.ActionResult {
            recorded += "openApp($app, $args)"
            return DesktopActions.ActionResult.ok(mapOf("pid" to 1234L))
        }
        override fun focusWindow(titleSubstring: String): DesktopActions.ActionResult {
            recorded += "focusWindow($titleSubstring)"
            return DesktopActions.ActionResult.ok(mapOf("title" to titleSubstring))
        }
        override fun typeText(text: String, perCharDelayMs: Long): DesktopActions.ActionResult {
            recorded += "typeText($text, $perCharDelayMs)"
            return DesktopActions.ActionResult.ok(mapOf("chars" to text.length))
        }
        override fun openUrl(url: String): DesktopActions.ActionResult {
            recorded += "openUrl($url)"
            return DesktopActions.ActionResult.ok(mapOf("url" to url))
        }
        override fun createLocalNote(title: String, body: String, directory: Path?): DesktopActions.ActionResult {
            recorded += "createLocalNote($title, $body, $directory)"
            return DesktopActions.ActionResult.ok(mapOf("path" to "/tmp/note.md"))
        }
        override fun showNotification(summary: String, body: String, urgency: String): DesktopActions.ActionResult {
            recorded += "showNotification($summary, $body, $urgency)"
            return DesktopActions.ActionResult.ok(mapOf("summary" to summary))
        }
        override fun getActiveWindow(): DesktopActions.ActionResult {
            recorded += "getActiveWindow()"
            return DesktopActions.ActionResult.ok(mapOf("title" to "Editor"))
        }
    }
    private val executor = NativeDesktopCommandExecutor(actions)

    @Test
    fun `OPEN_APP routes to openApp with allowlist payload`() {
        val result = executor.execute(envelope("OPEN_APP", mapOf("app" to "firefox", "args" to listOf("-private"))))
        assertEquals(CommandStatus.SUCCESS, result.status)
        assertEquals("openApp(firefox, [-private])", recorded.single())
        assertEquals("native-desktop", result.output["executor"])
        assertEquals("OPEN_APP", result.output["intent"])
        assertEquals(1234L, result.output["pid"])
    }

    @Test
    fun `OPEN_APP also accepts the legacy 'command' field`() {
        executor.execute(envelope("OPEN_APP", mapOf("command" to "code")))
        assertEquals("openApp(code, [])", recorded.single())
    }

    @Test
    fun `OPEN_APP fails clearly when app missing`() {
        val result = executor.execute(envelope("OPEN_APP", emptyMap()))
        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(result.errorReason!!.contains("'app' is required"))
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `FOCUS_WINDOW forwards title`() {
        val result = executor.execute(envelope("FOCUS_WINDOW", mapOf("title" to "Visual Studio Code")))
        assertEquals(CommandStatus.SUCCESS, result.status)
        assertEquals("focusWindow(Visual Studio Code)", recorded.single())
    }

    @Test
    fun `TYPE_TEXT carries delayMs`() {
        executor.execute(envelope("TYPE_TEXT", mapOf("text" to "hello", "delayMs" to 10)))
        assertEquals("typeText(hello, 10)", recorded.single())
    }

    @Test
    fun `TYPE_TEXT accepts numeric strings for delay`() {
        executor.execute(envelope("TYPE_TEXT", mapOf("text" to "x", "delayMs" to "25")))
        assertEquals("typeText(x, 25)", recorded.single())
    }

    @Test
    fun `OPEN_URL forwards url`() {
        executor.execute(envelope("OPEN_URL", mapOf("url" to "https://example.com")))
        assertEquals("openUrl(https://example.com)", recorded.single())
    }

    @Test
    fun `CREATE_LOCAL_NOTE forwards title body and optional directory`() {
        executor.execute(envelope("CREATE_LOCAL_NOTE", mapOf(
            "title" to "Standup",
            "body" to "- ship it",
            "directory" to "/tmp/notes"
        )))
        assertEquals("createLocalNote(Standup, - ship it, /tmp/notes)", recorded.single())
    }

    @Test
    fun `SHOW_NOTIFICATION uses default urgency when missing`() {
        executor.execute(envelope("SHOW_NOTIFICATION", mapOf("summary" to "Hello")))
        assertEquals("showNotification(Hello, , normal)", recorded.single())
    }

    @Test
    fun `GET_ACTIVE_WINDOW invokes action and returns title`() {
        val result = executor.execute(envelope("GET_ACTIVE_WINDOW", emptyMap()))
        assertEquals(CommandStatus.SUCCESS, result.status)
        assertEquals("Editor", result.output["title"])
    }

    @Test
    fun `unknown intent fails with explanatory reason`() {
        val result = executor.execute(envelope("rm -rf /", emptyMap()))
        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(result.errorReason!!.contains("not implemented in safe executor"))
    }

    @Test
    fun `missing intent fails fast`() {
        val result = executor.execute(envelope(null, emptyMap()))
        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(result.errorReason!!.contains("intent missing"))
    }

    @Test
    fun `intent matching is case insensitive`() {
        val result = executor.execute(envelope("open_app", mapOf("app" to "firefox")))
        assertEquals(CommandStatus.SUCCESS, result.status)
        assertEquals("openApp(firefox, [])", recorded.single())
    }

    @Test
    fun `executor exception is converted into FAILED result`() {
        val throwing = object : DesktopActions {
            override fun openApp(app: String, args: List<String>) = throw RuntimeException("boom")
            override fun focusWindow(titleSubstring: String) = error("nope")
            override fun typeText(text: String, perCharDelayMs: Long) = error("nope")
            override fun openUrl(url: String) = error("nope")
            override fun createLocalNote(title: String, body: String, directory: Path?) = error("nope")
            override fun showNotification(summary: String, body: String, urgency: String) = error("nope")
            override fun getActiveWindow() = error("nope")
        }
        val result = NativeDesktopCommandExecutor(throwing)
            .execute(envelope("OPEN_APP", mapOf("app" to "firefox")))
        assertEquals(CommandStatus.FAILED, result.status)
        assertNotNull(result.errorReason)
        assertTrue(result.errorReason!!.contains("boom"))
    }

    private fun envelope(intent: String?, payload: Map<String, Any?>): CommandEnvelope =
        CommandEnvelope().apply {
            commandId = "cmd-" + UUID.randomUUID()
            correlationId = "corr-" + UUID.randomUUID()
            this.intent = intent
            this.payload = payload
        }
}
