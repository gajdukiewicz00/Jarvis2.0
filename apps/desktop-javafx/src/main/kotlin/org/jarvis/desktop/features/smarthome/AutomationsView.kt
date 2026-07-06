package org.jarvis.desktop.features.smarthome

import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.features.common.ShellPanelSupport
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Automations" section embedded in the Smart Home route — trigger -> action
 * rules (e.g. motion on `hall_motion` turns on `hall_light`), each with a
 * dry-run "Simulate" button that reports what the rule WOULD do against its
 * trigger device's latest sensor reading(s) without ever actuating anything.
 *
 * Wires:
 *  - list rules -> GET  /api/v1/smarthome/automation/rules
 *  - simulate   -> POST /api/v1/smarthome/devices/{deviceId}/automation/simulate
 *
 * Embedded as a plain [Node] (not a [org.jarvis.desktop.shell.ShellRouteContent])
 * — the containing [SmartHomeView] owns the shell route lifecycle and forwards
 * [refresh] / [shutdown] calls into this section.
 */
class AutomationsView(
    apiClient: ApiClient
) : VBox(12.0) {
    private val readModel = AutomationsReadModel(apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-automations").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    private val statusPill = ShellPanelSupport.statusPill("Automations")
    private val statusLabel = ShellPanelSupport.sectionSubtitle(
        "Trigger -> action rules evaluated on incoming sensor readings. Live from `/api/v1/smarthome/automation/rules`."
    )
    private val refreshButton = Button("Refresh").apply {
        styleClass += "shell-action-button"
        setOnAction { refresh() }
    }

    private val rulesContainer = VBox(12.0)

    init {
        styleClass += "shell-section-card"
        children += HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += ShellPanelSupport.sectionTitle("Automations")
            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            children += statusPill
            children += refreshButton
        }
        children += statusLabel
        children += rulesContainer
        renderPlaceholder("Loading automation rules…")
    }

    fun refresh() {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        refreshButton.isDisable = true
        statusPill.text = "Loading"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")
        statusLabel.text = "Loading automation rules…"

        worker.execute {
            try {
                val rules = readModel.loadRules()
                Platform.runLater {
                    renderRules(rules)
                    statusPill.text = "Ready"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                    statusLabel.text = "${rules.size} rule(s)."
                }
            } catch (e: Exception) {
                Platform.runLater {
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    statusLabel.text = e.message ?: "Automation rules request failed."
                    renderPlaceholder("Unable to load automation rules.\n${e.message ?: "Unknown error"}")
                }
            } finally {
                inFlight.set(false)
                Platform.runLater { refreshButton.isDisable = false }
            }
        }
    }

    fun shutdown() {
        worker.shutdownNow()
    }

    private fun renderRules(rules: List<AutomationsReadModel.AutomationRule>) {
        if (rules.isEmpty()) {
            renderPlaceholder("No automation rules configured yet.")
            return
        }
        rulesContainer.children.setAll(rules.map(::ruleCard))
    }

    private fun ruleCard(rule: AutomationsReadModel.AutomationRule): Node {
        val resultLabel = Label("").apply {
            styleClass += "shell-section-subtitle"
            isWrapText = true
            isVisible = false
            isManaged = false
        }
        val simulateButton = Button("Simulate").apply {
            styleClass += "shell-action-button"
            setOnAction { simulateRule(rule, resultLabel, this) }
        }
        return VBox(8.0).apply {
            styleClass.addAll("shell-section-card", "automation-rule-card")
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children += Label(rule.name.ifBlank { rule.id }).apply { styleClass += "shell-section-title" }
                children += Label(if (rule.enabled) "Enabled" else "Disabled").apply {
                    styleClass += "shell-status-pill"
                    ShellPanelSupport.applyTone(
                        this,
                        if (rule.enabled) "shell-status-tone-success" else "shell-status-tone-muted"
                    )
                }
                val spacer = Region()
                HBox.setHgrow(spacer, Priority.ALWAYS)
                children += spacer
                children += simulateButton
            }
            children += Label(ruleSummary(rule)).apply {
                styleClass += "shell-section-subtitle"
                isWrapText = true
            }
            children += resultLabel
        }
    }

    private fun ruleSummary(rule: AutomationsReadModel.AutomationRule): String {
        val threshold = rule.triggerThreshold?.let { " (threshold $it)" } ?: ""
        val sensitive = if (rule.allowSensitiveActions) " [sensitive actions allowed]" else ""
        val payload = rule.actionPayload?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        return "IF ${rule.triggerDeviceId} ${triggerLabel(rule.triggerEvent)}$threshold " +
            "THEN ${rule.actionType} on ${rule.actionDeviceId}$payload$sensitive"
    }

    private fun triggerLabel(triggerEvent: String): String =
        triggerEvent.lowercase(Locale.ROOT).replace('_', ' ')

    private fun simulateRule(rule: AutomationsReadModel.AutomationRule, resultLabel: Label, button: Button) {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        button.isDisable = true
        resultLabel.isVisible = true
        resultLabel.isManaged = true
        resultLabel.text = "Simulating against ${rule.triggerDeviceId}'s latest reading(s)…"
        statusPill.text = "Simulating"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")

        worker.execute {
            try {
                val simulations = readModel.simulate(rule.triggerDeviceId)
                val matched = simulations.firstOrNull { it.ruleId == rule.id }
                Platform.runLater {
                    resultLabel.text = describeSimulation(rule, matched)
                    statusPill.text = "Ready"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                }
            } catch (e: Exception) {
                Platform.runLater {
                    resultLabel.text = "Simulation failed: ${e.message ?: "Unknown error"}"
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                }
            } finally {
                inFlight.set(false)
                Platform.runLater { button.isDisable = false }
            }
        }
    }

    private fun describeSimulation(rule: AutomationsReadModel.AutomationRule, simulation: AutomationsReadModel.RuleSimulation?): String {
        if (simulation == null) {
            return "Not triggered — no current sensor reading on ${rule.triggerDeviceId} matches this rule's condition."
        }
        val predicted = simulation.predictedAction
        return buildString {
            append(simulation.message ?: predicted?.message ?: "Rule triggered.")
            if (predicted != null && predicted.needsConfirmation) {
                append(" (would require explicit confirmation)")
            }
        }
    }

    private fun renderPlaceholder(message: String) {
        rulesContainer.children.setAll(
            VBox(6.0).apply {
                styleClass.addAll("shell-section-card", "shell-placeholder")
                children += Label(message).apply {
                    styleClass += "shell-placeholder-body"
                    isWrapText = true
                }
            }
        )
    }
}
