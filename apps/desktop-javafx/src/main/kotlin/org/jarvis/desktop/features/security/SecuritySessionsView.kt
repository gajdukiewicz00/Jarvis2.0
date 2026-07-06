package org.jarvis.desktop.features.security

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import org.jarvis.desktop.api.ApiClient
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.features.common.ShellPanelSupport
import org.jarvis.desktop.shell.ShellRouteContent
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Security Sessions & Audit panel — OWNER-only view of the token
 * issuance/rotation/revocation audit trail, plus single-token and
 * whole-user revoke actions. See [SecuritySessionsReadModel] for why the
 * audit trail stands in for a live session list.
 */
class SecuritySessionsView(
    apiClient: ApiClient
) : ScrollPane(), ShellRouteContent {
    private val readModel = SecuritySessionsReadModel(apiClient)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jarvis-desktop-app-security-sessions").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    private val statusPill = ShellPanelSupport.statusPill("Sessions")
    private val statusLabel = ShellPanelSupport.sectionSubtitle(
        "OWNER-only: revoke a single token, or every session for a user, and review recent audit events."
    )
    private val refreshButton = Button("Refresh audit").apply {
        styleClass += "shell-action-button"
        setOnAction { loadAudit() }
    }

    private val tokenField = TextField().apply { promptText = "Access or refresh token to revoke" }
    private val revokeReasonField = TextField().apply { promptText = "Reason (optional)" }
    private val revokeButton = Button("Revoke token").apply {
        styleClass += "shell-action-button-danger"
        setOnAction { revokeToken() }
    }

    private val userIdField = TextField().apply { promptText = "User id" }
    private val revokeAllReasonField = TextField().apply { promptText = "Reason (optional)" }
    private val revokeAllButton = Button("Revoke all sessions").apply {
        styleClass += "shell-action-button-danger"
        setOnAction { revokeAllForUser() }
    }

    private val revokeCurrentButton = Button("Revoke my current session").apply {
        styleClass += "shell-action-button-danger"
        setOnAction { confirmRevokeCurrentSession() }
    }

    private val actionResult = ShellPanelSupport.sectionSubtitle("")
    private val auditContainer = VBox(8.0)

    init {
        styleClass += "shell-route-scroll"
        styleClass += "shell-security-sessions-view"
        isFitToWidth = true
        hbarPolicy = ScrollBarPolicy.NEVER
        vbarPolicy = ScrollBarPolicy.AS_NEEDED
        content = buildContent()
        renderPlaceholder("Refresh to load the audit trail.")
    }

    override fun onRouteActivated() {
        loadAudit()
    }

    override fun onShellShutdown() {
        worker.shutdownNow()
    }

    private fun buildContent(): Node {
        val header = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children += VBox(6.0).apply {
                children += Label("Security Sessions & Audit").apply { styleClass += "shell-page-title" }
                children += Label("Revoke tokens/sessions and review the security audit trail.").apply {
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

        val revokeCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Revoke a session")
            children += statusLabel
            children += FlowPane(12.0, 12.0).apply {
                children.addAll(tokenField, revokeReasonField, revokeButton)
            }
            children += FlowPane(12.0, 12.0).apply {
                children.addAll(userIdField, revokeAllReasonField, revokeAllButton)
            }
            children += FlowPane(12.0, 12.0).apply {
                children.addAll(revokeCurrentButton)
            }
            children += actionResult
        }

        val auditCard = VBox(12.0).apply {
            styleClass += "shell-section-card"
            children += ShellPanelSupport.sectionTitle("Recent audit events")
            children += auditContainer
        }

        return VBox(18.0).apply {
            padding = Insets(24.0)
            children.addAll(header, revokeCard, auditCard)
        }
    }

    private fun loadAudit() {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        refreshButton.isDisable = true
        statusPill.text = "Loading"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")

        worker.execute {
            try {
                val events = readModel.listAudit()
                Platform.runLater {
                    renderAudit(events)
                    statusPill.text = "Ready"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                }
            } catch (e: Exception) {
                Platform.runLater {
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    renderPlaceholder("Аудит временно недоступен.\n${e.message ?: "Unknown error"}")
                }
            } finally {
                inFlight.set(false)
                Platform.runLater { refreshButton.isDisable = false }
            }
        }
    }

    private fun revokeToken() {
        val token = tokenField.text?.trim().orEmpty()
        if (token.isBlank()) {
            actionResult.text = "Enter a token to revoke first."
            return
        }
        runAction("Revoking token…") {
            val result = readModel.revokeToken(token, revokeReasonField.text)
            val events = readModel.listAudit()
            Platform.runLater {
                tokenField.clear()
                renderAudit(events)
                actionResult.text = "Revoked ${result.tokenType ?: "token"} (jti=${result.jti ?: "?"})."
            }
        }
    }

    private fun revokeAllForUser() {
        val userId = userIdField.text?.trim().orEmpty()
        if (userId.isBlank()) {
            actionResult.text = "Enter a user id first."
            return
        }
        runAction("Revoking all sessions…") {
            val result = readModel.revokeAllForUser(userId, revokeAllReasonField.text)
            val events = readModel.listAudit()
            Platform.runLater {
                userIdField.clear()
                renderAudit(events)
                actionResult.text = "Revoked ${result.revokedRefreshTokens} refresh token(s) for user ${result.userId}."
            }
        }
    }

    /** HIGH-risk confirmation — revoking the current session invalidates this app's own login. */
    private fun confirmRevokeCurrentSession() {
        val dialog = Dialog<ButtonType>()
        dialog.title = "Revoke current session"
        dialog.headerText = "Revoke your own current session?"
        dialog.dialogPane.content = Label(
            "This immediately invalidates the access and refresh tokens this desktop app is using. " +
                "You will need to log in again."
        ).apply { isWrapText = true }
        val revokeButtonType = ButtonType("Revoke", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.setAll(revokeButtonType, ButtonType.CANCEL)

        val confirmed = dialog.showAndWait().orElse(ButtonType.CANCEL) == revokeButtonType
        if (confirmed) {
            revokeCurrentSession()
        }
    }

    private fun revokeCurrentSession() {
        val refreshToken = TokenManager.getRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            actionResult.text = "No refresh token available for this session."
            return
        }
        runAction("Revoking current session…") {
            val result = readModel.revokeCurrentSession(refreshToken)
            Platform.runLater {
                actionResult.text = "Revoked current session (accessJti=${result.accessJti ?: "?"})."
            }
        }
    }

    private fun runAction(progress: String, block: () -> Unit) {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        setBusy(true)
        statusPill.text = "Working"
        ShellPanelSupport.applyTone(statusPill, "shell-status-tone-info")
        actionResult.text = progress

        worker.execute {
            try {
                block()
                Platform.runLater {
                    statusPill.text = "Ready"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-success")
                }
            } catch (e: Exception) {
                Platform.runLater {
                    statusPill.text = "Unavailable"
                    ShellPanelSupport.applyTone(statusPill, "shell-status-tone-error")
                    actionResult.text = e.message ?: "Security request failed."
                }
            } finally {
                inFlight.set(false)
                Platform.runLater { setBusy(false) }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        refreshButton.isDisable = busy
        revokeButton.isDisable = busy
        revokeAllButton.isDisable = busy
        revokeCurrentButton.isDisable = busy
    }

    private fun renderAudit(events: List<SecuritySessionsReadModel.AuditEvent>) {
        if (events.isEmpty()) {
            renderPlaceholder("No audit events recorded yet.")
            return
        }
        auditContainer.children.setAll(events.map(::auditRow))
    }

    private fun auditRow(event: SecuritySessionsReadModel.AuditEvent): Node {
        return VBox(4.0).apply {
            styleClass += "shell-section-card"
            children += HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children += Label(event.eventType).apply { styleClass += "shell-section-title" }
                val spacer = Region()
                HBox.setHgrow(spacer, Priority.ALWAYS)
                children += spacer
                children += Label(event.occurredAt).apply { styleClass += "shell-section-subtitle" }
            }
            val meta = listOfNotNull(
                event.userId?.let { "user=$it" },
                event.tokenReference.takeIf { it.isNotBlank() }?.let { "token=$it" },
                event.reason?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                children += Label(meta).apply {
                    styleClass += "shell-section-subtitle"
                    isWrapText = true
                }
            }
        }
    }

    private fun renderPlaceholder(message: String) {
        auditContainer.children.setAll(
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
