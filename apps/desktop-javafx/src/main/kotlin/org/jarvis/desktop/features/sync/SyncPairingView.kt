package org.jarvis.desktop.features.sync

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextArea
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.features.common.ShellPanelSupport
import org.jarvis.desktop.shell.ShellRouteContent
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sync / Pairing panel — surfaces device pairing status for the Android sync
 * path. The gateway sync route is still being added backend-side, so this panel
 * probes the candidate endpoint and renders an honest "временно недоступно"
 * state when it is missing, rather than crashing.
 */
class SyncPairingView(
    apiClient: ApiClient
) : ScrollPane(), ShellRouteContent {
    private val readModel = SyncReadModel(apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-sync").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    private val statusPill = ShellPanelSupport.statusPill("Pairing")
    private val statusHeadline = Label("Checking pairing status…").apply {
        styleClass += "shell-section-title"
        isWrapText = true
    }
    private val statusDetail = ShellPanelSupport.sectionSubtitle(
        "Pairs your Android phone with the desktop assistant for cross-device sync. NodePort 30095 is open on the cluster; the phone must complete pairing."
    )
    private val rawArea = TextArea().apply {
        isEditable = false
        isWrapText = true
        prefRowCount = 8
        styleClass += "diagnostics-readonly-area"
    }
    private val refreshButton = Button("Refresh").apply {
        styleClass += "shell-action-button"
        setOnAction { refresh() }
    }

    init {
        styleClass += "shell-route-scroll"
        styleClass += "shell-sync-view"
        isFitToWidth = true
        hbarPolicy = ScrollBarPolicy.NEVER
        vbarPolicy = ScrollBarPolicy.AS_NEEDED
        content = buildContent()
    }

    override fun onRouteActivated() {
        refresh()
    }

    override fun onShellShutdown() {
        worker.shutdownNow()
    }

    private fun buildContent(): Node {
        val header = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += VBox(6.0).apply {
                children += Label("Sync / Pairing").apply { styleClass += "shell-page-title" }
                children += Label("Connect and monitor the Android companion.").apply {
                    styleClass += "shell-page-subtitle"
                    isWrapText = true
                }
            }
            val spacer = Region()
            HBox.setHgrow(spacer, Priority.ALWAYS)
            children += spacer
            children += statusPill
            children += refreshButton
        }

        val statusCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += statusHeadline
            children += statusDetail
        }

        val rawCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Raw status")
            children += rawArea
        }

        return VBox(18.0).apply {
            padding = Insets(24.0)
            children.addAll(header, statusCard, rawCard)
        }
    }

    private fun refresh() {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        refreshButton.isDisable = true
        statusPill.text = "Checking"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")

        worker.execute {
            val result = readModel.pairingStatus()
            Platform.runLater {
                when (result) {
                    is SyncReadModel.Result.Available -> {
                        statusPill.text = "Reachable"
                        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                        statusHeadline.text = "Pairing endpoint reachable"
                        statusDetail.text = "The sync surface responded. Review the raw status below."
                        rawArea.text = result.body
                    }
                    is SyncReadModel.Result.Unavailable -> {
                        statusPill.text = "Unavailable"
                        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-warning")
                        statusHeadline.text = "Pairing временно недоступно"
                        statusDetail.text = result.reason
                        rawArea.text = result.reason
                    }
                }
                refreshButton.isDisable = false
                inFlight.set(false)
            }
        }
    }
}
