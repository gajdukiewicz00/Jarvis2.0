package org.jarvis.desktop.service

import javafx.application.Platform
import kotlinx.serialization.json.*
import okhttp3.*
import org.jarvis.desktop.auth.JwtSubjectParser
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for receiving PC Control commands from the orchestrator/API gateway.
 * 
 * Handles messages like:
 * - {"type": "PC_ACTION", "action": "VOLUME_UP", "params": {"delta": 10}}
 * - {"type": "PC_ACTION", "action": "OPEN_APP", "params": {"app": "browser"}}
 * - {"type": "PC_ACTION", "action": "MEDIA_CONTROL", "params": {"action": "NEXT"}}
 */
class PcControlWebSocketClient(
    private val urlProvider: () -> String = { AppConfig.current().pcControlWebSocketUrl },
    private val systemControl: SystemControlService,
    private val onStatusChange: (String) -> Unit = {},
    private val authServiceFactory: () -> AuthService = { AuthService() },
    private val uiDispatcher: ((() -> Unit) -> Unit) = { action -> Platform.runLater(action) }
) : WebSocketListener() {

    internal companion object {
        private const val PRECONNECT_REFRESH_WINDOW_SECONDS = 30L

        fun buildIdentifyMessage(userId: String?, username: String?): String {
            val resolvedClientId = when {
                !userId.isNullOrBlank() -> "desktop-$userId"
                !username.isNullOrBlank() -> "desktop-$username"
                else -> "desktop-anonymous"
            }

            return buildJsonObject {
                put("type", "IDENTIFY")
                put("client", "desktop")
                put("clientId", resolvedClientId)
                if (!userId.isNullOrBlank()) {
                    put("userId", userId)
                }
                if (!username.isNullOrBlank()) {
                    put("username", username)
                }
                put("capabilities", buildJsonArray {
                    add("VOLUME_CONTROL")
                    add("MEDIA_CONTROL")
                    add("APP_CONTROL")
                    add("WINDOW_CONTROL")
                    add("HOTKEY")
                    add("NOTIFICATION")
                    add("SCENARIO")
                })
            }.toString()
        }

        fun describeAction(action: String, params: JsonObject): String {
            return when (action.uppercase()) {
                "NOTIFY", "NOTIFICATION" -> {
                    val title = params["title"]?.jsonPrimitive?.contentOrNull ?: "Jarvis"
                    val message = params["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (message.isBlank()) "Notification: $title" else "Notification: $title - $message"
                }
                "SCENARIO" -> {
                    val scenario = params["name"]?.jsonPrimitive?.contentOrNull
                        ?: params["scenario"]?.jsonPrimitive?.contentOrNull
                        ?: "unknown"
                    "Scenario: $scenario"
                }
                "VOLUME_UP" -> "Volume up ${params["delta"]?.jsonPrimitive?.intOrNull ?: 10}%"
                "VOLUME_DOWN" -> "Volume down ${params["delta"]?.jsonPrimitive?.intOrNull ?: 10}%"
                "OPEN_APP" -> {
                    val app = params["app"]?.jsonPrimitive?.contentOrNull
                        ?: params["appName"]?.jsonPrimitive?.contentOrNull
                        ?: "application"
                    "Open app: $app"
                }
                "OPEN_URL" -> {
                    val url = params["url"]?.jsonPrimitive?.contentOrNull ?: "url"
                    "Open url: $url"
                }
                else -> action.replace('_', ' ')
                    .lowercase()
                    .replaceFirstChar { it.titlecase() }
            }
        }

        fun formatStatusMessage(prefix: String, action: String, params: JsonObject, error: String? = null): String {
            val summary = describeAction(action, params)
            return when {
                error.isNullOrBlank() -> "$prefix $summary"
                else -> "$prefix $summary: $error"
            }
        }
    }

    private val logger = LoggerFactory.getLogger(PcControlWebSocketClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val stateLock = Any()
    private val configListener: (ResolvedDesktopConfig) -> Unit = { config ->
        handleEndpointChange(config.pcControlWebSocketUrl)
    }
    
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    // Runs the (potentially slow, blocking) SystemControlService invocation off the
    // JavaFX Application Thread. Only the resulting status/ack callbacks are marshalled
    // back via uiDispatcher; the OS command execution itself never touches the FX thread.
    private val actionExecutor: ExecutorService = Executors.newSingleThreadExecutor(
        ThreadFactory { runnable ->
            Thread(runnable, "jarvis-desktop-pc-control-action").apply { isDaemon = true }
        }
    )

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10
    private var authRefreshAttempts = 0
    private val maxAuthRefreshAttempts = 1
    private var authRecoveryInProgress = false
    @Volatile private var resolvedUrl: String = urlProvider()
    private var configListenerRegistered = false
    
    fun connect() {
        if (isConnected) {
            logger.debug("Already connected to PC Control WebSocket")
            return
        }

        ensureConfigListener()
        resolvedUrl = urlProvider()
        
        logger.info("🔌 Connecting to PC Control WebSocket: $resolvedUrl")
        updateStatus("Connecting...")
        
        val requestBuilder = Request.Builder()
            .url(resolvedUrl)

        var token = TokenManager.getAccessToken()
        maybeRefreshExpiringAccessToken(token)
        token = TokenManager.getAccessToken()
        if (token.isNullOrBlank() && !TokenManager.getRefreshToken().isNullOrBlank()) {
            attemptTokenRefresh("missing access token before PC control websocket connect", clearTokensOnFailure = false)
            token = TokenManager.getAccessToken()
        }
        if (!token.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        } else {
            logger.debug("No access token available for PC Control WS - connecting without Authorization header")
        }
        val request = requestBuilder.build()
            
        webSocket = client.newWebSocket(request, this)
    }
    
    fun disconnect() {
        logger.info("Disconnecting from PC Control WebSocket")
        webSocket?.close(1000, "Client disconnecting")
        webSocket = null
        isConnected = false
        updateStatus("Disconnected")
        removeConfigListener()
    }
    
    fun isConnected(): Boolean = isConnected
    
    override fun onOpen(webSocket: WebSocket, response: Response) {
        isConnected = true
        reconnectAttempts = 0
        authRefreshAttempts = 0
        logger.info("✅ Connected to PC Control WebSocket")
        updateStatus("Connected")

        webSocket.send(buildIdentifyMessage(TokenManager.getUserId(), TokenManager.getUsername()))
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        logger.debug("Received message: $text")
        
        try {
            val jsonObj = json.parseToJsonElement(text).jsonObject
            val type = jsonObj["type"]?.jsonPrimitive?.content
            
            when (type) {
                "PC_ACTION" -> handlePcAction(jsonObj)
                "PING" -> webSocket.send("""{"type": "PONG"}""")
                "ACK" -> logger.debug("Command acknowledged")
                else -> logger.warn("Unknown message type: $type")
            }
        } catch (e: Exception) {
            logger.error("Error parsing message: $text", e)
        }
    }

    private fun handlePcAction(jsonObj: JsonObject) {
        val action = jsonObj["action"]?.jsonPrimitive?.content ?: return
        val requestId = jsonObj["requestId"]?.jsonPrimitive?.contentOrNull
        val params = jsonObj["params"]?.jsonObject ?: buildJsonObject { }
        val actionSummary = describeAction(action, params)
        
        logger.info("🎯 Executing PC action: action={}, requestId={}, params={}", action, requestId, params)
        updateStatus("Executing $actionSummary")

        actionExecutor.execute {
            try {
                fun missingParameterResult(vararg names: String): Result<Unit> {
                    val joined = names.joinToString(" or ")
                    return Result.failure(IllegalArgumentException("Missing required parameter: $joined"))
                }

                fun firstText(vararg names: String): String? {
                    return names.asSequence()
                        .mapNotNull { params[it]?.jsonPrimitive?.contentOrNull }
                        .firstOrNull()
                }

                val result = when (action.uppercase()) {
                    // Volume
                    "VOLUME_UP" -> {
                        val delta = params["delta"]?.jsonPrimitive?.intOrNull ?: 10
                        systemControl.changeVolume(delta, "+")
                    }
                    "VOLUME_DOWN" -> {
                        val delta = params["delta"]?.jsonPrimitive?.intOrNull ?: 10
                        systemControl.changeVolume(delta, "-")
                    }
                    "VOLUME_SET" -> {
                        val level = params["level"]?.jsonPrimitive?.intOrNull ?: 50
                        systemControl.setVolume(level)
                    }
                    "MUTE" -> systemControl.mute()
                    "UNMUTE" -> systemControl.unmute()
                    "MUTE_TOGGLE" -> systemControl.toggleMute()
                    
                    // Media
                    "MEDIA_CONTROL", "MEDIA" -> {
                        val mediaAction = params["action"]?.jsonPrimitive?.content ?: "PLAY_PAUSE"
                        systemControl.mediaControl(mediaAction)
                    }
                    "PLAY", "PAUSE", "PLAY_PAUSE", "NEXT", "PREV", "PREVIOUS", "STOP" -> {
                        systemControl.mediaControl(action)
                    }
                    
                    // Apps
                    "OPEN_APP" -> {
                        val app = firstText("app", "appName")
                        if (app == null) missingParameterResult("app", "appName")
                        else systemControl.openApp(app)
                    }
                    "OPEN_URL" -> {
                        val url = firstText("url")
                        if (url == null) missingParameterResult("url")
                        else systemControl.openUrl(url)
                    }
                    
                    // Hotkeys
                    "HOTKEY", "KEY" -> {
                        val keys = firstText("keys", "combination", "keyCombination")
                        if (keys == null) missingParameterResult("keys", "combination", "keyCombination")
                        else systemControl.executeHotkey(keys)
                    }
                    
                    // Windows
                    "WINDOW" -> {
                        val windowAction = firstText("action")
                        val target = params["target"]?.jsonPrimitive?.content
                        if (windowAction == null) missingParameterResult("action")
                        else systemControl.windowAction(windowAction, target)
                    }
                    "MINIMIZE" -> systemControl.windowAction("MINIMIZE")
                    "MAXIMIZE" -> systemControl.windowAction("MAXIMIZE")
                    "FULLSCREEN" -> systemControl.windowAction("FULLSCREEN")
                    "LOCK_SCREEN" -> systemControl.lockScreen()
                    
                    // Notifications
                    "NOTIFY", "NOTIFICATION" -> {
                        val title = params["title"]?.jsonPrimitive?.content ?: "Jarvis"
                        val message = params["message"]?.jsonPrimitive?.content ?: ""
                        systemControl.showNotification(title, message)
                    }
                    
                    // Scenarios
                    "SCENARIO" -> {
                        val scenario = firstText("name", "scenario")
                        if (scenario == null) missingParameterResult("name", "scenario")
                        else systemControl.executeScenario(scenario)
                    }
                    "SYSTEM_COMMAND" -> {
                        when (firstText("command")?.lowercase()) {
                            "sleep" -> systemControl.sleepMode()
                            "monitor_off" -> systemControl.turnMonitorOff()
                            null -> missingParameterResult("command")
                            else -> Result.failure(IllegalArgumentException("Unknown system command"))
                        }
                    }
                    "WORK_MODE" -> systemControl.executeScenario("work")
                    "REST_MODE" -> systemControl.executeScenario("rest")
                    "FOCUS_MODE" -> systemControl.executeScenario("focus")
                    
                    // Beep
                    "BEEP" -> systemControl.beep()
                    
                    else -> {
                        logger.warn("Unknown action: $action")
                        Result.failure(IllegalArgumentException("Unknown action: $action"))
                    }
                }
                
                if (result.isSuccess) {
                    logger.info("✓ Action completed: action={}, requestId={}", action, requestId)
                    updateStatus(formatStatusMessage("✓", action, params))
                    sendAck(requestId, action, true)
                } else {
                    logger.error(
                        "✗ Action failed: action={}, requestId={}, error={}",
                        action,
                        requestId,
                        result.exceptionOrNull()?.message
                    )
                    updateStatus(formatStatusMessage("✗", action, params, result.exceptionOrNull()?.message ?: "failed"))
                    sendAck(requestId, action, false, result.exceptionOrNull()?.message)
                }
            } catch (e: Exception) {
                logger.error("Error executing action: action={}, requestId={}", action, requestId, e)
                updateStatus(formatStatusMessage("✗", action, params, e.message ?: "unexpected error"))
                sendAck(requestId, action, false, e.message)
            }
        }
    }
    
    private fun sendAck(requestId: String?, action: String, success: Boolean, error: String? = null) {
        val ack = buildJsonObject {
            put("type", "ACK")
            put("action", action)
            if (!requestId.isNullOrBlank()) {
                put("requestId", requestId)
            }
            put("success", success)
            if (error != null) put("error", error)
        }
        logger.info(
            "📤 Sending PC action ACK: requestId={}, action={}, success={}, error={}",
            requestId,
            action,
            success,
            error
        )
        webSocket?.send(ack.toString())
    }

    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
        // Binary messages not expected for PC control
        logger.debug("Received binary message (ignored)")
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        isConnected = false
        val formatted = TransportErrorFormatter.describeClose("PC Control WebSocket", resolvedUrl, code, reason)
        logger.info("{}", formatted.diagnosticMessage)
        updateStatus("Disconnected")
    }
    
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        isConnected = false
        val formatted = TransportErrorFormatter.describeClose("PC Control WebSocket", resolvedUrl, code, reason)
        logger.info("{}", formatted.diagnosticMessage)
        updateStatus(if (code == 1000) "Disconnected" else "Connection failed: ${formatted.userMessage}")
        
        // Auto-reconnect if not intentional close
        if (code != 1000 && reconnectAttempts < maxReconnectAttempts) {
            scheduleReconnect()
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        isConnected = false
        if (response?.code == 401 && handleUnauthorizedFailure("PC Control WebSocket upgrade rejected with HTTP 401")) {
            return
        }

        val formatted = TransportErrorFormatter.describeFailure("PC Control WebSocket", resolvedUrl, t, response)
        logger.warn("{}", formatted.diagnosticMessage, t)
        updateStatus("Connection failed: ${formatted.userMessage}")

        if (reconnectAttempts < maxReconnectAttempts) {
            scheduleReconnect()
        }
    }

    private fun maybeRefreshExpiringAccessToken(currentToken: String?) {
        if (currentToken.isNullOrBlank()) {
            return
        }

        val refreshToken = TokenManager.getRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            return
        }

        val expirationEpochSeconds = JwtSubjectParser.extractExpirationEpochSeconds(currentToken) ?: return
        val secondsUntilExpiry = expirationEpochSeconds - (System.currentTimeMillis() / 1000L)
        if (secondsUntilExpiry > PRECONNECT_REFRESH_WINDOW_SECONDS) {
            return
        }

        val refreshReason = if (secondsUntilExpiry <= 0) {
            "cached access token expired before PC control websocket connect"
        } else {
            "cached access token expires in ${secondsUntilExpiry}s before PC control websocket connect"
        }

        if (!attemptTokenRefresh(refreshReason, clearTokensOnFailure = false)) {
            logger.warn(
                "PC Control pre-connect token refresh failed; proceeding with cached access token ({})",
                refreshReason
            )
        }
    }

    private fun handleUnauthorizedFailure(reason: String): Boolean {
        synchronized(stateLock) {
            if (authRecoveryInProgress) {
                logger.info("PC Control auth recovery already in progress; skipping duplicate recovery trigger")
                return true
            }
            authRecoveryInProgress = true
        }

        try {
            if (authRefreshAttempts < maxAuthRefreshAttempts && attemptTokenRefresh(reason)) {
                authRefreshAttempts++
                logger.info("🔐 PC Control token refresh succeeded, reconnecting immediately")
                reconnectAttempts = 0
                Thread {
                    Thread.sleep(300L)
                    if (!isConnected) {
                        connect()
                    }
                }.apply {
                    isDaemon = true
                    name = "jarvis-desktop-pc-control-auth-reconnect"
                    start()
                }
                return true
            }

            logger.warn("🔐 PC Control auth recovery failed; desktop executor login is required")
            return true
        } finally {
            synchronized(stateLock) {
                authRecoveryInProgress = false
            }
        }
    }

    private fun attemptTokenRefresh(reason: String, clearTokensOnFailure: Boolean = true): Boolean {
        val refreshToken = TokenManager.getRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            logger.warn("Cannot refresh PC Control auth: refresh token missing ({})", reason)
            if (clearTokensOnFailure) {
                TokenManager.clearTokens()
            }
            return false
        }

        return try {
            logger.info("Refreshing PC Control auth token ({})", reason)
            val authResponse = authServiceFactory().refreshTokens(refreshToken)
            TokenManager.saveTokens(
                authResponse.accessToken,
                authResponse.refreshToken,
                authResponse.username,
                authResponse.role
            )
            true
        } catch (e: Exception) {
            val formatted = TransportErrorFormatter.describeFailure(
                channel = "PC Control auth refresh",
                endpoint = "${AppConfig.current().apiGatewayBaseUrl}/auth/refresh",
                throwable = e
            )
            logger.error("{}", formatted.diagnosticMessage, e)
            if (clearTokensOnFailure) {
                TokenManager.clearTokens()
            }
            false
        }
    }
    
    private fun scheduleReconnect() {
        reconnectAttempts++
        val delay = (reconnectAttempts * 2).coerceAtMost(30) // Max 30 seconds
        logger.info("Scheduling reconnect attempt $reconnectAttempts in ${delay}s")
        updateStatus("Reconnecting in ${delay}s...")
        
        Thread {
            Thread.sleep(delay * 1000L)
            if (!isConnected) {
                connect()
            }
        }.apply {
            isDaemon = true
            name = "jarvis-desktop-pc-control-reconnect"
            start()
        }
    }
    
    private fun updateStatus(status: String) {
        uiDispatcher { onStatusChange(status) }
    }

    private fun handleEndpointChange(newUrl: String) {
        if (newUrl == resolvedUrl) {
            return
        }

        val previousUrl = resolvedUrl
        resolvedUrl = newUrl
        logger.info("PC Control WebSocket endpoint updated: {} -> {}", previousUrl, newUrl)

        val existingSocket = webSocket
        isConnected = false
        webSocket = null
        existingSocket?.close(1000, "Endpoint updated")

        Thread {
            Thread.sleep(200L)
            connect()
        }.apply {
            isDaemon = true
            name = "jarvis-desktop-pc-control-endpoint-switch"
            start()
        }
    }

    private fun ensureConfigListener() {
        if (!configListenerRegistered) {
            AppConfig.addListener(configListener)
            configListenerRegistered = true
        }
    }

    private fun removeConfigListener() {
        if (configListenerRegistered) {
            AppConfig.removeListener(configListener)
            configListenerRegistered = false
        }
    }
}
