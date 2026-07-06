package org.jarvis.desktop.features.controlcenter

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tooltip
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.features.ai.AiReadModel
import org.jarvis.desktop.features.status.ServiceStatusReadModel
import org.jarvis.desktop.shell.ShellRoute
import org.jarvis.desktop.shell.ShellRouteContent
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Control Center — a cinematic landing dashboard plus the full feature index.
 *
 * The feature index and informational panels below are static, but the
 * headline system-status badge and the "14B Brain" status card are backed by
 * live reads ([ServiceStatusReadModel], [AiReadModel]) — the same sources
 * [org.jarvis.desktop.features.status.ServiceStatusView] and
 * [org.jarvis.desktop.features.ai.AiView] use — so this landing screen never
 * claims "ALL CORE SYSTEMS READY" or a specific model/GPU while something is
 * actually down or unverified. Construction itself does no network I/O; the
 * first refresh happens on [onRouteActivated] on a background thread.
 * Navigation reuses the existing shell routes via [onNavigate].
 */
class ControlCenterView(
    private val onNavigate: (ShellRoute) -> Unit,
    private val serviceStatusReadModel: ServiceStatusReadModel = ServiceStatusReadModel(),
    private val aiReadModel: AiReadModel = AiReadModel(tokenProvider = { TokenManager.getAccessToken() })
) : ScrollPane(), ShellRouteContent {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-control-center").apply { isDaemon = true }
    }
    private val refreshInFlight = AtomicBoolean(false)

    private val overallBadge = Label("CHECKING SYSTEMS…")
    private val brainStatusDot = statusDot(ACCENT_BLUE)
    private val brainStatusBadge = Label("Checking…")
    private val brainDetailLabel = Label("Checking model and GPU status…").apply {
        style = "-fx-text-fill: #8aa0c0; -fx-font-size: 12px;"
        isWrapText = true
        maxWidth = 230.0
    }

    init {
        isFitToWidth = true
        styleClass += "control-center"
        style = "-fx-background: #0b0f17; -fx-background-color: #0b0f17;"
        applyBadgeStyle(overallBadge, ACCENT_BLUE)
        applyBadgeStyle(brainStatusBadge, ACCENT_BLUE)
        content = buildContent()
    }

    override fun onRouteActivated() {
        refreshLiveStatus()
    }

    override fun onShellShutdown() {
        worker.shutdownNow()
    }

    private fun refreshLiveStatus() {
        if (!refreshInFlight.compareAndSet(false, true)) {
            return
        }
        worker.execute {
            val serviceSnapshot = runCatching { serviceStatusReadModel.refresh() }.getOrNull()
            val aiSnapshot = runCatching { aiReadModel.refresh() }.getOrNull()
            Platform.runLater {
                serviceSnapshot?.let(::renderOverallBadge)
                aiSnapshot?.let(::renderBrainStatus)
                refreshInFlight.set(false)
            }
        }
    }

    private fun renderOverallBadge(snapshot: ServiceStatusReadModel.Snapshot) {
        val total = snapshot.services.size
        val down = snapshot.downServices
        val (text, color) = when {
            total == 0 -> "CHECKING SYSTEMS…" to ACCENT_BLUE
            down.isEmpty() -> "ALL CORE SYSTEMS READY" to ACCENT_GREEN
            down.size == total -> "ALL CORE SYSTEMS DOWN" to ACCENT_RED
            else -> "${snapshot.healthyCount}/$total SYSTEMS READY — ${down.size} DOWN" to ACCENT_AMBER
        }
        overallBadge.text = text
        applyBadgeStyle(overallBadge, color)
        overallBadge.tooltip = if (down.isEmpty()) {
            Tooltip("All $total service(s) reachable (target ${snapshot.baseUrl}).")
        } else {
            Tooltip(
                "Down/degraded (target ${snapshot.baseUrl}):\n" +
                    down.joinToString("\n") { svc -> "- ${svc.name} (${svc.status.name})" }
            )
        }
    }

    private fun renderBrainStatus(snapshot: AiReadModel.Snapshot) {
        val color = when (snapshot.overallStatus) {
            AiReadModel.AiStatus.READY -> ACCENT_GREEN
            AiReadModel.AiStatus.DEGRADED, AiReadModel.AiStatus.STARTING -> ACCENT_AMBER
            AiReadModel.AiStatus.DOWN, AiReadModel.AiStatus.ERROR -> ACCENT_RED
            AiReadModel.AiStatus.DISABLED -> ACCENT_MUTED
        }
        brainStatusDot.style = "-fx-background-color: $color; -fx-background-radius: 6;"
        brainStatusBadge.text = snapshot.overallStatus.name
        applyBadgeStyle(brainStatusBadge, color)

        val model = snapshot.model.effectiveLlmModel.takeUnless { it.isBlank() || it.equals("unknown", true) }
            ?: snapshot.model.llmModel.takeUnless { it.isBlank() || it.equals("unknown", true) }
            ?: "Model unknown"
        val gpu = snapshot.gpu
        val gpuDescription = when {
            gpu.available && gpu.gpuName.isNotBlank() -> "GPU ${gpu.gpuName}"
            gpu.available -> "GPU active"
            gpu.device.equals("cpu", true) -> "CPU only"
            gpu.device.equals("unknown", true) || gpu.readinessStatus.equals("unknown", true) -> "GPU status unknown"
            else -> "GPU unavailable"
        }
        val provider = snapshot.llm.provider.takeUnless { it.isBlank() || it.equals("unknown", true) } ?: "unknown backend"
        brainDetailLabel.text = "$model  ·  $gpuDescription  ·  via $provider"
    }

    private fun applyBadgeStyle(label: Label, color: String) {
        label.style = "-fx-background-color: ${color}22; -fx-text-fill: $color; " +
            "-fx-background-radius: 8; -fx-padding: 4 10 4 10; -fx-font-size: 11px; -fx-font-weight: bold;"
    }

    private fun buildContent(): Node {
        val root = VBox(22.0).apply {
            padding = Insets(28.0, 32.0, 36.0, 32.0)
            style = "-fx-background-color: #0b0f17;"
        }
        root.children.addAll(
            header(),
            sectionTitle("All Features"),
            featureIndex(),
            sectionTitle("System Status"),
            statusGrid(),
            twoColumn(
                panel("✅ What Works Now", ACCENT_GREEN, WHAT_WORKS),
                panel("🙋 Needs Human", ACCENT_BLUE, NEEDS_HUMAN)
            ),
            twoColumn(
                panel("🎙 Voice Demo Checklist", ACCENT_PURPLE, VOICE_CHECKLIST),
                panel("🗣 Ты можешь сказать", ACCENT_GREEN, VOICE_COMMANDS)
            ),
            runCommands()
        )
        return root
    }

    // ---- header -------------------------------------------------------------
    private fun header(): Node {
        val title = Label("J.A.R.V.I.S. — Control Center").apply {
            style = "-fx-text-fill: #eaf2ff; -fx-font-size: 28px; -fx-font-weight: bold;"
        }
        // Model name intentionally omitted here — the "14B Brain" status card below
        // is the single source of truth for the actual model/GPU/backend in use.
        val subtitle = Label("Local cinematic assistant · on-device inference").apply {
            style = "-fx-text-fill: #8aa0c0; -fx-font-size: 13px;"
        }
        // Static build stamp — confirms the operator is looking at the new UI.
        // Intentionally NOT time-based so the value stays deterministic.
        val build = Label("UI build $UI_BUILD · full feature index · voice + brain + memory + finance + PC + vision").apply {
            style = "-fx-text-fill: #5f7691; -fx-font-size: 11px;"
        }
        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        val top = HBox(12.0, VBox(4.0, title, subtitle, build), spacer, overallBadge).apply {
            alignment = Pos.CENTER_LEFT
        }
        return top
    }

    // ---- feature index (navigable tiles) ------------------------------------
    private fun featureIndex(): Node {
        val grid = FlowPane(16.0, 16.0)
        FEATURES.forEach { feature -> grid.children += featureTile(feature) }
        return grid
    }

    private fun featureTile(feature: Feature): Node {
        val name = Label(feature.title).apply {
            style = "-fx-text-fill: #eaf2ff; -fx-font-size: 15px; -fx-font-weight: bold;"
        }
        val detail = Label(feature.detail).apply {
            style = "-fx-text-fill: #8aa0c0; -fx-font-size: 12px;"
            isWrapText = true
            maxWidth = 220.0
        }
        val open = Button("Open").apply {
            style = "-fx-background-color: ${feature.accent}22; -fx-text-fill: ${feature.accent}; " +
                "-fx-background-radius: 8; -fx-padding: 6 14 6 14; -fx-font-weight: bold;"
            setOnAction { onNavigate(feature.route) }
        }
        val tile = VBox(
            10.0,
            HBox(10.0, statusDot(feature.accent), name).apply { alignment = Pos.CENTER_LEFT },
            detail,
            open
        )
        tile.padding = Insets(16.0)
        tile.prefWidth = 248.0
        tile.style = "-fx-background-color: #131a27; -fx-background-radius: 14; " +
            "-fx-border-color: ${feature.accent}33; -fx-border-radius: 14; -fx-border-width: 1;"
        return tile
    }

    // ---- status cards -------------------------------------------------------
    private fun statusGrid(): Node {
        val grid = FlowPane(16.0, 16.0)
        grid.children += brainStatusCard()
        SERVICES.forEach { grid.children += statusCard(it) }
        return grid
    }

    /**
     * Live card for the local AI brain — unlike [statusCard], the badge/detail here are
     * mutable fields kept in sync with [AiReadModel] via [renderBrainStatus] so this never
     * hardcodes a specific model or GPU (see class doc).
     */
    private fun brainStatusCard(): Node {
        val name = Label("14B Brain").apply {
            style = "-fx-text-fill: #eaf2ff; -fx-font-size: 15px; -fx-font-weight: bold;"
        }
        val card = VBox(10.0, HBox(10.0, brainStatusDot, name).apply {
            alignment = Pos.CENTER_LEFT
        }, brainStatusBadge, brainDetailLabel)
        card.padding = Insets(16.0)
        card.prefWidth = 262.0
        card.style = "-fx-background-color: #131a27; -fx-background-radius: 14; " +
            "-fx-border-color: #1f2a3d; -fx-border-radius: 14; -fx-border-width: 1;"
        return card
    }

    private fun statusCard(svc: ServiceStatus): Node {
        val name = Label(svc.name).apply {
            style = "-fx-text-fill: #eaf2ff; -fx-font-size: 15px; -fx-font-weight: bold;"
        }
        val detail = Label(svc.detail).apply {
            style = "-fx-text-fill: #8aa0c0; -fx-font-size: 12px;"
            isWrapText = true
            maxWidth = 230.0
        }
        val card = VBox(10.0, HBox(10.0, statusDot(svc.state.color), name).apply {
            alignment = Pos.CENTER_LEFT
        }, badge(svc.state.label, svc.state.color), detail)
        card.padding = Insets(16.0)
        card.prefWidth = 262.0
        card.style = "-fx-background-color: #131a27; -fx-background-radius: 14; " +
            "-fx-border-color: #1f2a3d; -fx-border-radius: 14; -fx-border-width: 1;"
        return card
    }

    // ---- panels -------------------------------------------------------------
    private fun panel(title: String, accent: String, lines: List<String>): Node {
        val head = Label(title).apply {
            style = "-fx-text-fill: $accent; -fx-font-size: 16px; -fx-font-weight: bold;"
        }
        val box = VBox(8.0).apply { children += head }
        lines.forEach { line ->
            box.children += Label("•  $line").apply {
                style = "-fx-text-fill: #c7d4e8; -fx-font-size: 13px;"
                isWrapText = true
            }
        }
        box.padding = Insets(18.0)
        box.style = "-fx-background-color: #111826; -fx-background-radius: 14; " +
            "-fx-border-color: ${accent}33; -fx-border-radius: 14; -fx-border-width: 1;"
        HBox.setHgrow(box, Priority.ALWAYS)
        box.maxWidth = Double.MAX_VALUE
        return box
    }

    private fun twoColumn(left: Node, right: Node): Node =
        HBox(16.0, left, right).apply { alignment = Pos.TOP_LEFT }

    private fun runCommands(): Node {
        val head = Label("⌨  Run These").apply {
            style = "-fx-text-fill: #eaf2ff; -fx-font-size: 16px; -fx-font-weight: bold;"
        }
        val box = VBox(6.0).apply { children += head }
        RUN_COMMANDS.forEach { (label, cmd) ->
            box.children += Label(label).apply {
                style = "-fx-text-fill: #8aa0c0; -fx-font-size: 12px;"
            }
            box.children += Label(cmd).apply {
                style = "-fx-text-fill: #7ee7c7; -fx-font-family: 'monospace'; -fx-font-size: 12px;"
                isWrapText = true
            }
        }
        box.padding = Insets(18.0)
        box.style = "-fx-background-color: #0e1420; -fx-background-radius: 14; " +
            "-fx-border-color: #1f2a3d; -fx-border-radius: 14; -fx-border-width: 1;"
        return box
    }

    // ---- small helpers ------------------------------------------------------
    private fun sectionTitle(text: String): Node = Label(text).apply {
        style = "-fx-text-fill: #6f86a8; -fx-font-size: 12px; -fx-font-weight: bold;"
    }

    private fun badge(text: String, color: String): Node = Label(text).apply {
        style = "-fx-background-color: ${color}22; -fx-text-fill: $color; " +
            "-fx-background-radius: 8; -fx-padding: 4 10 4 10; -fx-font-size: 11px; -fx-font-weight: bold;"
    }

    private fun statusDot(color: String): Node = Region().apply {
        prefWidth = 12.0; prefHeight = 12.0; minWidth = 12.0; minHeight = 12.0
        style = "-fx-background-color: $color; -fx-background-radius: 6;"
    }

    private enum class State(val label: String, val color: String) {
        READY("READY", ACCENT_GREEN),
        CONFIRMATION("CONFIRMATION REQUIRED", ACCENT_PURPLE),
        NEEDS_OPERATOR("NEEDS OPERATOR", ACCENT_BLUE),
        DEGRADED("DEGRADED", ACCENT_AMBER)
    }

    private data class ServiceStatus(val name: String, val state: State, val detail: String)

    private data class Feature(
        val title: String,
        val detail: String,
        val route: ShellRoute,
        val accent: String
    )

    private companion object {
        const val UI_BUILD = "control-center-v2"

        const val ACCENT_GREEN = "#36d399"
        const val ACCENT_BLUE = "#5aa9ff"
        const val ACCENT_PURPLE = "#b48cff"
        const val ACCENT_AMBER = "#f4bf4f"
        const val ACCENT_RED = "#f4606e"
        const val ACCENT_MUTED = "#5f7691"

        val FEATURES = listOf(
            // Model name intentionally generic — the live "14B Brain" status card is the
            // single source of truth for which model/GPU/backend is actually in use.
            Feature("Brain / AI Chat", "Talk to the local on-device brain.", ShellRoute.BRAIN, ACCENT_PURPLE),
            Feature("Voice Commands", "Live catalog: ты можешь сказать…", ShellRoute.VOICE_HELP, ACCENT_GREEN),
            Feature("Voice Control", "Mic, STT/TTS, voice diagnostics.", ShellRoute.VOICE, ACCENT_GREEN),
            Feature("Memory", "Semantic recall + Obsidian search.", ShellRoute.MEMORY, ACCENT_BLUE),
            Feature("Finance", "Bank parser, transactions, expenses.", ShellRoute.FINANCE, ACCENT_AMBER),
            Feature("Planner", "Focus, evening review, tasks.", ShellRoute.PLANNER, ACCENT_BLUE),
            Feature("Life / Wellness", "Wellness log and weekly summary.", ShellRoute.LIFE, ACCENT_GREEN),
            Feature("Analytics", "Spend, time, and calendar summaries.", ShellRoute.ANALYTICS, ACCENT_BLUE),
            Feature("Analytics Insights", "Day score, forecast, report.", ShellRoute.INSIGHTS, ACCENT_PURPLE),
            Feature("Smart Home", "Devices and quick actions.", ShellRoute.SMART_HOME, ACCENT_GREEN),
            Feature("PC Control", "Safe reads + screenshot action.", ShellRoute.PC_CONTROL, ACCENT_AMBER),
            Feature("Vision Security", "Owner verification, CV pipeline.", ShellRoute.VISION_SECURITY, ACCENT_BLUE),
            Feature("Proactive", "Recent proactive observations.", ShellRoute.PROACTIVE, ACCENT_PURPLE),
            Feature("Security / Privacy", "Toggle privacy mode on/off.", ShellRoute.SECURITY, ACCENT_AMBER),
            Feature("Security Sessions & Audit", "Revoke sessions, review audit trail.", ShellRoute.SECURITY_SESSIONS, ACCENT_AMBER),
            Feature("Agent Swarm", "Roles, tasks, dry-run swarm + report.", ShellRoute.AGENT_SWARM, ACCENT_PURPLE),
            Feature("Media Jobs", "Async media job status + artifacts.", ShellRoute.MEDIA_JOBS, ACCENT_BLUE),
            Feature("Finance Review Inbox", "Approve/reject/edit draft transactions.", ShellRoute.FINANCE_REVIEW, ACCENT_AMBER),
            Feature("Sync / Pairing", "Android companion pairing.", ShellRoute.SYNC, ACCENT_BLUE),
            Feature("Diagnostics", "Status report + host health.", ShellRoute.DIAGNOSTICS, ACCENT_GREEN),
            Feature("AI Runtime", "LLM/GPU lifecycle and status.", ShellRoute.AI, ACCENT_PURPLE),
            Feature("Service Status", "Model/service health + repair, update, rollback ops.", ShellRoute.SERVICE_STATUS, ACCENT_GREEN),
            Feature("Settings", "Endpoint, locale, session.", ShellRoute.SETTINGS, ACCENT_BLUE)
        )

        // "14B Brain" is rendered live via [brainStatusCard] — see class doc; it is
        // intentionally not duplicated as a static entry here.
        val SERVICES = listOf(
            ServiceStatus("RAG Memory", State.READY, "pgvector recall proven end-to-end"),
            ServiceStatus("Obsidian Search", State.READY, "Semantic note search; duplicates cleaned"),
            ServiceStatus("Voice Gateway", State.READY, "Sessions, runtime + WebSocket healthy"),
            ServiceStatus("STT (Vosk)", State.READY, "EN + RU models loaded, offline"),
            ServiceStatus("TTS (Piper)", State.READY, "Neural voice, real WAV output"),
            ServiceStatus("PC Control", State.CONFIRMATION, "Safe reads live; actions need confirm"),
            ServiceStatus("Proactive Awareness", State.NEEDS_OPERATOR, "Observes + reasons; speech needs speakers"),
            ServiceStatus("Android Sync", State.NEEDS_OPERATOR, "NodePort 30095 open; needs phone + pairing")
        )

        val WHAT_WORKS = listOf(
            "14B brain chat (qwen3-14b)",
            "RAG memory recall",
            "Obsidian semantic search",
            "Voice intent: тише → volume_down, громче → volume_up, выключи звук → mute",
            "PC-control safe reads (volume, windows, system info)",
            "Confirmation gate (APPROVED accepted, command queued)"
        )

        val NEEDS_HUMAN = listOf(
            "Microphone + speakers for the live spoken loop",
            "This display to view the GUI (you are looking at it)",
            "Desktop agent connected to execute confirmed PC actions",
            "Android phone for pairing / mobile E2E"
        )

        val VOICE_COMMANDS = listOf(
            "«сделай тише / громче / выключи звук» — управление звуком",
            "«открой браузер / терминал» · «переключись на терминал»",
            "«сделай скриншот / заблокируй экран» (с подтверждением)",
            "«напомни купить кофе» · «запомни что я люблю эспрессо»",
            "«который час» · «потратил 500 на обед»",
            "«что на экране?» — Jarvis опишет активное окно (OCR)"
        )

        val VOICE_CHECKLIST = listOf(
            "1. Speak a command (e.g. \"сделай тише\")",
            "2. Intent resolved by NLP (volume_down)",
            "3. Confirmation required (risk = MEDIUM)",
            "4. Approve via voice / confirmation endpoint",
            "5. Action executes, then restore volume"
        )

        val RUN_COMMANDS = listOf(
            "Health + demo readiness" to "./scripts/jarvis-demo-check.sh",
            "Repair endpoint if needed" to "./scripts/jarvis-final-check.sh --repair",
            "Test real voice (mic + speakers)" to
                "./scripts/jarvis-voice-smoke.sh --record 5 --tts-out /tmp/jarvis-demo.wav && aplay /tmp/jarvis-demo.wav",
            "Controlled volume confirmation demo" to "./scripts/jarvis-demo-check.sh --approve-volume-demo"
        )
    }
}
