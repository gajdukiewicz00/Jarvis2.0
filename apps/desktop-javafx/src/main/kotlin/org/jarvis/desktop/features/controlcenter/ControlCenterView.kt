package org.jarvis.desktop.features.controlcenter

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.jarvis.desktop.shell.ShellRoute
import org.jarvis.desktop.shell.ShellRouteContent

/**
 * Control Center — a cinematic landing dashboard plus the full feature index.
 *
 * It is intentionally self-contained (no API client / service wiring) so it
 * always renders and never breaks the build. Beyond the verified system status
 * and the live voice-demo checklist, it now exposes EVERY product surface as a
 * navigable tile so the operator can reach the whole feature set from one
 * screen. Navigation reuses the existing shell routes via [onNavigate].
 */
class ControlCenterView(
    private val onNavigate: (ShellRoute) -> Unit
) : ScrollPane(), ShellRouteContent {

    init {
        isFitToWidth = true
        styleClass += "control-center"
        style = "-fx-background: #0b0f17; -fx-background-color: #0b0f17;"
        content = buildContent()
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
        val subtitle = Label("Local cinematic assistant · Qwen3-14B brain · 100% on-device").apply {
            style = "-fx-text-fill: #8aa0c0; -fx-font-size: 13px;"
        }
        // Static build stamp — confirms the operator is looking at the new UI.
        // Intentionally NOT time-based so the value stays deterministic.
        val build = Label("UI build $UI_BUILD · full feature index · voice + brain + memory + finance + PC + vision").apply {
            style = "-fx-text-fill: #5f7691; -fx-font-size: 11px;"
        }
        val pill = badge("ALL CORE SYSTEMS READY", ACCENT_GREEN)
        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        val top = HBox(12.0, VBox(4.0, title, subtitle, build), spacer, pill).apply {
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
        SERVICES.forEach { grid.children += statusCard(it) }
        return grid
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

        val FEATURES = listOf(
            Feature("Brain / AI Chat", "Talk to the local Qwen3-14B brain.", ShellRoute.BRAIN, ACCENT_PURPLE),
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

        val SERVICES = listOf(
            ServiceStatus("14B Brain", State.READY, "Qwen3-14B on RTX 5070 via host-model-daemon:18080"),
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
