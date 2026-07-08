package org.jarvis.desktop.e2e

import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.Accordion
import javafx.scene.control.Labeled
import javafx.scene.control.ScrollPane
import javafx.scene.control.SplitPane
import javafx.scene.control.TabPane
import javafx.scene.control.TextInputControl
import javafx.scene.control.TitledPane
import javafx.scene.control.ToolBar
import java.util.IdentityHashMap
import okhttp3.mockwebserver.MockWebServer
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.config.ConfigSource
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared support for headless JavaFX end-to-end UI journey tests.
 *
 * These tests construct the REAL feature Views (scene graph + wired button
 * handlers), point them at a [MockWebServer] standing in for the api-gateway,
 * simulate a user action by firing the actual control, then assert BOTH that
 * the expected HTTP request reached the backend AND that the visible scene
 * graph reacted (labels/tables/status text updated).
 *
 * This differs from the existing ReadModel tests, which bypass the UI layer
 * entirely. Here the assertion surface is the widget tree the user sees.
 *
 * The Monocle software Glass/Prism pipeline is started ONCE per JVM here so
 * the many E2E classes don't race on [Platform.startup]. If the toolkit
 * cannot boot in this environment, [ensureToolkit] flips a flag and the
 * per-test [onFx]/[waitForFx] helpers `assumeTrue`-skip rather than hang.
 */
object E2eFx {

    @Volatile
    private var toolkitStarted = false
    private var toolkitAttempted = false
    private val lock = Any()

    private const val FX_TASK_TIMEOUT_SECONDS = 15L
    private const val DEFAULT_WAIT_MS = 10_000L
    private const val POLL_INTERVAL_MS = 40L

    /** Idempotently boot the headless JavaFX toolkit. Safe to call from any thread. */
    fun ensureToolkit() {
        if (toolkitStarted || toolkitAttempted && !toolkitStarted) {
            if (toolkitAttempted) return
        }
        synchronized(lock) {
            if (toolkitAttempted) return
            toolkitAttempted = true
            System.setProperty("testfx.robot", "glass")
            System.setProperty("testfx.headless", "true")
            System.setProperty("prism.order", "sw")
            System.setProperty("prism.text", "t2k")
            System.setProperty("glass.platform", "Monocle")
            System.setProperty("monocle.platform", "Headless")
            System.setProperty("java.awt.headless", "true")
            // Views auto-load from the (real) ApiClient on a background thread during
            // construction. HttpURLConnection keep-alive can pool a connection to a
            // MockWebServer that a prior test has since shut down; a later reuse then
            // stalls for the full 10s connect/read timeout, backing up the FX thread.
            // Disable pooling so every test's request opens a fresh socket.
            System.setProperty("http.keepAlive", "false")
            System.setProperty("http.maxConnections", "1")

            val latch = CountDownLatch(1)
            try {
                Platform.startup { latch.countDown() }
            } catch (alreadyStarted: IllegalStateException) {
                latch.countDown()
            }
            toolkitStarted = latch.await(10, TimeUnit.SECONDS)
        }
    }

    /** True once the toolkit has booted; tests may gate on this. */
    fun toolkitAvailable(): Boolean {
        ensureToolkit()
        return toolkitStarted
    }

    /** Run [block] on the JavaFX Application Thread and return its result, propagating exceptions. */
    fun <T> onFx(block: () -> T): T {
        ensureToolkit()
        assumeTrue(toolkitStarted, "JavaFX toolkit did not start headlessly in this environment — skipping E2E")
        if (Platform.isFxApplicationThread()) return block()
        val latch = CountDownLatch(1)
        val result = AtomicReference<Any?>()
        val failure = AtomicReference<Throwable?>()
        Platform.runLater {
            try {
                result.set(block())
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                latch.countDown()
            }
        }
        check(latch.await(FX_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "FX task did not complete in time" }
        failure.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.get() as T
    }

    /**
     * Poll [predicate] (evaluated on the FX thread) until it holds or the timeout elapses.
     * Use after firing an action that triggers async backend load + a Platform.runLater UI update.
     */
    fun waitForFx(timeoutMs: Long = DEFAULT_WAIT_MS, description: String = "FX condition", predicate: () -> Boolean) {
        ensureToolkit()
        assumeTrue(toolkitStarted, "JavaFX toolkit did not start headlessly — skipping E2E")
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (onFx { predicate() }) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for: $description")
    }

    /** A [ResolvedDesktopConfig] whose api-gateway + api base URLs point at [server]. */
    fun configFor(server: MockWebServer): () -> ResolvedDesktopConfig {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        return {
            ResolvedDesktopConfig(
                apiGatewayBaseUrl = baseUrl,
                apiBaseUrl = "$baseUrl/api/v1",
                voiceWebSocketUrl = "$baseUrl/ws/voice".replaceFirst("http", "ws"),
                pcControlWebSocketUrl = "$baseUrl/ws/pc-control".replaceFirst("http", "ws"),
                locale = Locale.ENGLISH,
                voiceLanguage = "en-US",
                apiGatewaySource = ConfigSource.MANUAL_PERSISTED_SETTINGS,
                apiGatewayReason = "e2e test",
                usesManualEndpointOverride = true
            )
        }
    }

    /** An [ApiClient] wired to [server]. */
    fun apiClientFor(server: MockWebServer): ApiClient = ApiClient(configFor(server))

    // ---- scene-graph helpers (call within onFx { } or waitForFx { }) ----

    /**
     * Depth-first walk of the scene graph rooted at [root], deduplicated by identity.
     *
     * Also descends into container "content" nodes that a headless (unskinned)
     * JavaFX control does NOT expose through [Parent.getChildrenUnmodifiable]:
     * [ScrollPane], [TitledPane], [TabPane] tab content, [SplitPane] items,
     * [Accordion] panes, and [ToolBar] items. Without this, a test rooted at a
     * View that extends ScrollPane would see zero descendants unless a real
     * Scene/skin were attached (which can hang under Monocle).
     */
    fun allNodes(root: Node): Sequence<Node> = sequence {
        val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Node, Boolean>())
        val stack = ArrayDeque<Node>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (!seen.add(node)) continue
            yield(node)
            // Push children in reverse so they pop in document order (pre-order DFS),
            // keeping `first {}` / `find {}` deterministic and matching visual order.
            for (child in childrenOf(node).asReversed()) stack.addLast(child)
        }
    }

    /** Children of [node], including content nodes hidden when a control is unskinned. */
    private fun childrenOf(node: Node): List<Node> {
        val kids = ArrayList<Node>()
        if (node is Parent) kids.addAll(node.childrenUnmodifiable)
        when (node) {
            is ScrollPane -> node.content?.let(kids::add)
            is TitledPane -> node.content?.let(kids::add)
            is TabPane -> node.tabs.forEach { tab -> tab.content?.let(kids::add) }
            is SplitPane -> kids.addAll(node.items)
            is Accordion -> kids.addAll(node.panes)
            is ToolBar -> kids.addAll(node.items)
        }
        return kids
    }

    /** All nodes of type [T] under [root]. */
    inline fun <reified T : Node> findAll(root: Node): List<T> = allNodes(root).filterIsInstance<T>().toList()

    /** First node of type [T] under [root], or null. */
    inline fun <reified T : Node> find(root: Node): T? = allNodes(root).filterIsInstance<T>().firstOrNull()

    /** True if any Labeled/TextInput node under [root] renders text containing [text] (case-insensitive). */
    fun hasText(root: Node, text: String): Boolean = allNodes(root).any { node ->
        when (node) {
            is Labeled -> node.text?.contains(text, ignoreCase = true) == true
            is TextInputControl -> node.text?.contains(text, ignoreCase = true) == true
            else -> false
        }
    }

    /** Concatenated visible text of all Labeled/TextInput nodes under [root] (for diagnostics/assertions). */
    fun visibleText(root: Node): String = allNodes(root).mapNotNull { node ->
        when (node) {
            is Labeled -> node.text
            is TextInputControl -> node.text
            else -> null
        }
    }.filter { it.isNotBlank() }.joinToString(" | ")
}
