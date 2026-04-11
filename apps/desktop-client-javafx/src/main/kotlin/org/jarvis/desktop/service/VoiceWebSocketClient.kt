package org.jarvis.desktop.service

import javafx.application.Platform
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.jarvis.desktop.auth.JwtSubjectParser
import org.jarvis.desktop.auth.TokenManager
import org.jarvis.desktop.config.AppConfig
import org.jarvis.desktop.config.ResolvedDesktopConfig
import org.jarvis.desktop.config.VoiceRecognitionLanguage
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for voice streaming and receiving transcripts/responses.
 * 
 * This client handles:
 * - Sending audio chunks to the server
 * - Receiving partial and final transcripts
 * - Receiving action responses and TTS audio
 * - Auto-reconnect on connection failures
 * 
 * The isSendingAllowed flag is controlled by VoiceSession to ensure
 * audio is only sent during the LISTENING state.
 */
class VoiceWebSocketClient(
    private val urlProvider: () -> String = { AppConfig.current().voiceWebSocketUrl },
    private val onStateChange: (String) -> Unit,
    private val onTranscript: (String, Boolean, String?) -> Unit, // text, isFinal, correlationId
    private val onResponse: (String, String?, Boolean) -> Unit,   // text, action, handled
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onSttStatusChanged: (available: Boolean, reason: String?) -> Unit = { _, _ -> },
    private val onTtsStatusChanged: (available: Boolean, reason: String?) -> Unit = { _, _ -> },
    private val authServiceFactory: () -> AuthService = { AuthService() },
    private val uiDispatcher: ((() -> Unit) -> Unit) = { action -> Platform.runLater(action) }
) : WebSocketListener() {
    companion object {
        private const val PRECONNECT_REFRESH_WINDOW_SECONDS = 30L
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Disable timeout for long-lived connection
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "voice-ws-reconnect").apply { isDaemon = true }
    }
    private val logger = LoggerFactory.getLogger(VoiceWebSocketClient::class.java)
    private val messageFactory = VoiceWebSocketMessageFactory()
    private val configListener: (ResolvedDesktopConfig) -> Unit = { config ->
        handleResolvedConfigChange(config)
    }
    private val stateLock = Any()
    
    private var webSocket: WebSocket? = null
    var isConnected = false
        private set
    @Volatile private var resolvedUrl: String = urlProvider()
    @Volatile private var currentVoiceLanguage: String = AppConfig.current().voiceLanguage
    private var configListenerRegistered = false
    
    /** Flag to control audio sending - set by VoiceSession based on current state */
    @Volatile var isSendingAllowed = false
    
    // Current command's correlation ID for tracing
    @Volatile var currentCorrelationId: String? = null
    
    // Auto-reconnect state
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private var shouldReconnect = true
    private var authRefreshAttempts = 0
    private val maxAuthRefreshAttempts = 1
    private var connectInProgress = false
    private var authRecoveryInProgress = false
    private var pendingReconnectTicket = 0L
    private var pendingReconnectFuture: ScheduledFuture<*>? = null

    private enum class ConnectDecision {
        START,
        SKIP,
        DEFER
    }

    private fun dispatchToUi(action: () -> Unit) {
        uiDispatcher(action)
    }

    fun connect() {
        connectInternal("manual connect")
    }

    private fun connectInternal(trigger: String, reservationHeld: Boolean = false) {
        if (!reservationHeld) {
            when (prepareConnect(trigger)) {
                ConnectDecision.SKIP -> return
                ConnectDecision.DEFER -> {
                    requestReconnect(100L, "$trigger waiting for auth recovery")
                    return
                }
                ConnectDecision.START -> Unit
            }
        }

        ensureConfigListener()
        val config = AppConfig.current()
        resolvedUrl = urlProvider()
        currentVoiceLanguage = config.voiceLanguage
        dispatchToUi { onStateChange("CONNECTING") }

        val requestBuilder = Request.Builder()
            .url(resolvedUrl)

        var token = TokenManager.getAccessToken()
        maybeRefreshExpiringAccessToken(token)
        token = TokenManager.getAccessToken()
        if (token.isNullOrBlank() && !TokenManager.getRefreshToken().isNullOrBlank()) {
            attemptTokenRefresh("missing access token before voice websocket connect")
            token = TokenManager.getAccessToken()
        }
        if (!token.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        } else {
            logger.debug("No access token available for Voice WS - connecting without Authorization header")
        }

        val userId = TokenManager.getUserId()
        if (!userId.isNullOrBlank()) {
            requestBuilder.addHeader("X-User-Id", userId)
        }

        val username = TokenManager.getUsername()
        if (!username.isNullOrBlank()) {
            requestBuilder.addHeader("X-Username", username)
        }

        val role = TokenManager.getUserRole()
        if (!role.isNullOrBlank()) {
            requestBuilder.addHeader("X-User-Roles", role)
        }

        val request = requestBuilder.build()
        
        logger.info("🔌 Connecting to Voice WebSocket: {}", resolvedUrl)
        logger.info(
            "🔐 Voice WS auth context: tokenPresent={}, userId={}, username={}, roles={}",
            !token.isNullOrBlank(),
            userId ?: "",
            username ?: "",
            role ?: ""
        )
        try {
            webSocket = client.newWebSocket(request, this)
        } catch (e: Exception) {
            synchronized(stateLock) {
                connectInProgress = false
            }
            val formatted = TransportErrorFormatter.describeFailure("Voice WebSocket", resolvedUrl, e)
            logger.error("❌ {}", formatted.diagnosticMessage, e)
            dispatchToUi { onStateChange("ERROR: ${formatted.userMessage}") }
            if (shouldReconnect) {
                scheduleReconnect()
            }
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
            "cached access token expired before voice websocket connect"
        } else {
            "cached access token expires in ${secondsUntilExpiry}s before voice websocket connect"
        }

        if (!attemptTokenRefresh(refreshReason, clearTokensOnFailure = false)) {
            logger.warn(
                "Voice WS pre-connect token refresh failed; proceeding with cached access token and keeping 401 recovery enabled ({})",
                refreshReason
            )
        }
    }
    
    fun disconnect() {
        shouldReconnect = false
        cancelPendingReconnect()
        webSocket?.close(1000, "Client disconnecting")
        webSocket = null
        isConnected = false
        synchronized(stateLock) {
            connectInProgress = false
            authRecoveryInProgress = false
        }
        onStateChange("DISCONNECTED")
        removeConfigListener()
    }

    private fun handleEndpointChange(newUrl: String) {
        if (newUrl == resolvedUrl) {
            return
        }

        val previousUrl = resolvedUrl
        resolvedUrl = newUrl
        logger.info("Voice WebSocket endpoint updated: {} -> {}", previousUrl, newUrl)

        if (!shouldReconnect && !isConnected) {
            return
        }

        val existingSocket = webSocket
        synchronized(stateLock) {
            isConnected = false
            connectInProgress = false
            webSocket = null
        }
        existingSocket?.close(1000, "Endpoint updated")
        requestReconnect(200L, "endpoint updated")
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

    private fun prepareConnect(trigger: String): ConnectDecision {
        synchronized(stateLock) {
            if (isConnected) {
                logger.debug("Skipping Voice WS connect ({}): socket is already connected", trigger)
                return ConnectDecision.SKIP
            }
            if (connectInProgress) {
                logger.debug("Skipping Voice WS connect ({}): another connect is already in progress", trigger)
                return ConnectDecision.SKIP
            }
            if (authRecoveryInProgress) {
                logger.info("Deferring Voice WS connect ({}): auth recovery still running", trigger)
                return ConnectDecision.DEFER
            }

            shouldReconnect = true
            cancelPendingReconnectLocked()
            connectInProgress = true
            return ConnectDecision.START
        }
    }

    private fun requestReconnect(delayMs: Long, reason: String) {
        val ticket = synchronized(stateLock) {
            if (!shouldReconnect) {
                return
            }
            cancelPendingReconnectLocked()
            pendingReconnectTicket += 1
            pendingReconnectTicket.also { scheduledTicket ->
                pendingReconnectFuture = reconnectExecutor.schedule(
                    { executeReconnectRequest(scheduledTicket, reason) },
                    delayMs,
                    TimeUnit.MILLISECONDS
                )
            }
        }
        logger.debug("Queued Voice WS reconnect ticket={} in {}ms ({})", ticket, delayMs, reason)
    }

    private fun executeReconnectRequest(ticket: Long, reason: String) {
        synchronized(stateLock) {
            if (ticket != pendingReconnectTicket) {
                return
            }
            pendingReconnectFuture = null
            if (!shouldReconnect || isConnected) {
                return
            }
            if (connectInProgress) {
                logger.debug("Skipping Voice WS reconnect ticket={} ({}): connect already in progress", ticket, reason)
                return
            }
            if (authRecoveryInProgress) {
                pendingReconnectFuture = reconnectExecutor.schedule(
                    { executeReconnectRequest(ticket, reason) },
                    100L,
                    TimeUnit.MILLISECONDS
                )
                logger.debug("Deferring Voice WS reconnect ticket={} ({}): waiting for auth recovery", ticket, reason)
                return
            }
            connectInProgress = true
        }

        logger.info("🔌 Attempting Voice WS reconnect ({})...", reason)
        connectInternal(reason, reservationHeld = true)
    }

    private fun cancelPendingReconnect() {
        synchronized(stateLock) {
            cancelPendingReconnectLocked()
        }
    }

    private fun cancelPendingReconnectLocked() {
        pendingReconnectFuture?.cancel(false)
        pendingReconnectFuture = null
        pendingReconnectTicket += 1
    }

    private fun isCurrentSocket(candidate: WebSocket): Boolean {
        synchronized(stateLock) {
            return webSocket === candidate
        }
    }
    
    private fun scheduleReconnect() {
        if (!shouldReconnect || reconnectAttempts >= maxReconnectAttempts) {
            logger.warn("🔌 Not reconnecting: shouldReconnect={}, attempts={}/{}", 
                shouldReconnect, reconnectAttempts, maxReconnectAttempts)
            if (reconnectAttempts >= maxReconnectAttempts) {
                dispatchToUi {
                    onStateChange("UNAVAILABLE: voice backend not reachable after $maxReconnectAttempts attempts")
                }
            }
            return
        }
        
        reconnectAttempts++
        val delay = minOf(reconnectAttempts * reconnectAttempts * 2, 30) // Exponential: 2, 8, 18, 30, 30
        logger.info("🔌 Scheduling Voice WS reconnect attempt {}/{} in {}s", 
            reconnectAttempts, maxReconnectAttempts, delay)
        
        dispatchToUi { onStateChange("Reconnecting in ${delay}s (attempt $reconnectAttempts/$maxReconnectAttempts)...") }
        requestReconnect(delay * 1000L, "backoff attempt $reconnectAttempts/$maxReconnectAttempts")
    }
    
    /**
     * Start a new voice command session with the given correlationId.
     * The correlationId is generated by VoiceSession and passed here to ensure
     * a single unified ID is used across the entire pipeline.
     * 
     * @param correlationId The unified correlation ID from VoiceSession
     */
    fun startCommand(correlationId: String) {
        currentCorrelationId = correlationId
        
        if (!isConnected) {
            logger.warn("⚠️ Cannot start command - not connected, correlationId={}", correlationId)
            return
        }
        
        val config = AppConfig.current()
        currentVoiceLanguage = VoiceRecognitionLanguage.normalize(config.voiceLanguage)
        val msg = messageFactory.startMessage(correlationId, currentVoiceLanguage)

        logger.info(
            "🎤 Starting voice command, correlationId={}, locale={}, voiceLanguage={}",
            correlationId,
            config.locale.toLanguageTag(),
            currentVoiceLanguage
        )
        webSocket?.send(msg)
    }
    
    fun sendAudio(data: ByteArray) {
        if (!isConnected) return
        if (!isSendingAllowed) {
            // Skip sending audio when not in LISTENING state (TTS playback, cooldown, etc.)
            return
        }
        logger.debug("📤 Sending audio chunk: {} bytes, correlationId={}", data.size, currentCorrelationId)
        webSocket?.send(data.toByteString())
    }
    
    fun sendConfig(config: Map<String, String>) {
        if (!isConnected || config.isEmpty()) return
        val message = messageFactory.configMessage(config)
        logger.info("🎛️ Sending voice config: payload={}", message)
        webSocket?.send(message)
    }

    fun endOfSpeech() {
        if (!isConnected) return
        val msg = buildJsonObject {
            put("type", "END")
            put("correlationId", currentCorrelationId ?: "")
        }.toString()
        logger.info("⏹️ Sending end-of-speech marker, correlationId={}", currentCorrelationId)
        webSocket?.send(msg)
    }
    
    /**
     * Request TTS phrase for STT timeout ("Sir, I couldn't hear you").
     * Used when the listen timeout expires without receiving a final transcript.
     */
    fun requestTimeoutPhrase() {
        if (!isConnected) return
        val msg = buildJsonObject {
            put("type", "TIMEOUT")
            put("correlationId", currentCorrelationId ?: "")
        }.toString()
        logger.info("⏰ Requesting timeout phrase, correlationId={}", currentCorrelationId)
        webSocket?.send(msg)
    }
    
    /**
     * Clear current correlation ID after command processing is complete.
     */
    fun clearCorrelationId() {
        logger.debug("Clearing correlationId={}", currentCorrelationId)
        currentCorrelationId = null
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        synchronized(stateLock) {
            if (this.webSocket !== webSocket) {
                logger.debug("Ignoring stale Voice WS open callback for {}", response.request.url)
                webSocket.close(1000, "Superseded by newer connection")
                return
            }
            connectInProgress = false
            isConnected = true
            reconnectAttempts = 0 // Reset on successful connection
            authRefreshAttempts = 0
        }
        logger.info("🔌 Voice WS connected: ${response.request.url}")
        dispatchToUi { onStateChange("CONNECTED") }
        val config = AppConfig.current()
        currentVoiceLanguage = VoiceRecognitionLanguage.normalize(config.voiceLanguage)
        logger.info(
            "🎛️ Voice WS initial recognition config: locale={}, voiceLanguage={}",
            config.locale.toLanguageTag(),
            currentVoiceLanguage
        )
        sendConfig(mapOf("language" to currentVoiceLanguage))
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (!isCurrentSocket(webSocket)) {
            logger.debug("Ignoring stale Voice WS text message")
            return
        }
        try {
            val json = Json.parseToJsonElement(text).jsonObject
            val type = json["type"]?.jsonPrimitive?.content
            val msgCorrelationId = json["correlationId"]?.jsonPrimitive?.contentOrNull
            
            dispatchToUi {
                when (type) {
                    "STATE" -> {
                        val state = json["state"]?.jsonPrimitive?.content ?: "UNKNOWN"
                        val sttAvailable = json["sttAvailable"]?.jsonPrimitive?.booleanOrNull
                        val ttsAvailable = json["ttsAvailable"]?.jsonPrimitive?.booleanOrNull
                        val ttsReason = json["ttsReason"]?.jsonPrimitive?.contentOrNull
                        onStateChange(state)
                        if (sttAvailable != null) {
                            onSttStatusChanged(sttAvailable, null)
                        }
                        if (ttsAvailable != null) {
                            onTtsStatusChanged(ttsAvailable, ttsReason)
                        }
                    }
                    "TRANSCRIPT_PARTIAL" -> {
                        val transcript = json["text"]?.jsonPrimitive?.content ?: ""
                        logger.debug("📝 Partial transcript: '{}', correlationId={}", transcript, msgCorrelationId)
                        onTranscript(transcript, false, msgCorrelationId)
                    }
                    "TRANSCRIPT_FINAL" -> {
                        val transcript = json["text"]?.jsonPrimitive?.content ?: ""
                        logger.info("📝 Final transcript: '{}', correlationId={}", transcript, msgCorrelationId)
                        // IMPORTANT: Notify about final transcript immediately
                        // so VoiceSession can stop recording ASAP
                        onTranscript(transcript, true, msgCorrelationId)
                    }
                    "RESPONSE" -> {
                        val responseText = json["text"]?.jsonPrimitive?.content ?: ""
                        val action = json["action"]?.jsonPrimitive?.contentOrNull
                        val handled = json["handled"]?.jsonPrimitive?.booleanOrNull ?: false
                        val recognized = json["recognized"]?.jsonPrimitive?.booleanOrNull
                        val actionResolved = json["actionResolved"]?.jsonPrimitive?.booleanOrNull
                        val executorFound = json["executorFound"]?.jsonPrimitive?.booleanOrNull
                        val executionAttempted = json["executionAttempted"]?.jsonPrimitive?.booleanOrNull
                        val executionSucceeded = json["executionSucceeded"]?.jsonPrimitive?.booleanOrNull
                        val executionFailed = json["executionFailed"]?.jsonPrimitive?.booleanOrNull
                        val failureReason = json["failureReason"]?.jsonPrimitive?.contentOrNull
                        logger.info(
                            "📢 Response: '{}', action={}, handled={}, recognized={}, actionResolved={}, executorFound={}, executionAttempted={}, executionSucceeded={}, executionFailed={}, failureReason={}, correlationId={}",
                            responseText,
                            action,
                            handled,
                            recognized,
                            actionResolved,
                            executorFound,
                            executionAttempted,
                            executionSucceeded,
                            executionFailed,
                            failureReason,
                            msgCorrelationId
                        )
                        if (action == "STT_UNAVAILABLE") {
                            onSttStatusChanged(false, responseText)
                        }
                        if (action == "TTS_UNAVAILABLE") {
                            onTtsStatusChanged(false, responseText)
                        }
                        onResponse(responseText, action, handled)
                    }
                    "ACTION_RESULT" -> {
                        val success = json["success"]?.jsonPrimitive?.booleanOrNull ?: false
                        val message = json["message"]?.jsonPrimitive?.contentOrNull ?: ""
                        logger.info("✅ Action result: success={}, message='{}', correlationId={}", 
                            success, message, msgCorrelationId)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error parsing message: {}", text, e)
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
        if (!isCurrentSocket(webSocket)) {
            logger.debug("Ignoring stale Voice WS audio frame")
            return
        }
        // Received TTS audio
        val audioData = bytes.toByteArray()
        dispatchToUi { onAudioReceived(audioData) }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        if (!isCurrentSocket(webSocket)) {
            val formatted = TransportErrorFormatter.describeClose("Voice WebSocket", resolvedUrl, code, reason)
            logger.debug("Ignoring stale Voice WS closing callback: {}", formatted.diagnosticMessage)
            return
        }
        val formatted = TransportErrorFormatter.describeClose("Voice WebSocket", resolvedUrl, code, reason)
        logger.warn("⚠️ {}", formatted.diagnosticMessage)
        // Don't set isConnected=false here, wait for onClosed
    }
    
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        synchronized(stateLock) {
            if (this.webSocket !== webSocket) {
                val formatted = TransportErrorFormatter.describeClose("Voice WebSocket", resolvedUrl, code, reason)
                logger.debug("Ignoring stale Voice WS closed callback: {}", formatted.diagnosticMessage)
                return
            }
            connectInProgress = false
            this.webSocket = null
            isConnected = false
        }
        val formatted = TransportErrorFormatter.describeClose("Voice WebSocket", resolvedUrl, code, reason)
        logger.warn("🔌 {}", formatted.diagnosticMessage)
        dispatchToUi { onStateChange("DISCONNECTED") }
        
        // Auto-reconnect if not intentional close (1000 = normal close)
        if (code != 1000 && shouldReconnect) {
            scheduleReconnect()
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        synchronized(stateLock) {
            if (this.webSocket !== webSocket) {
                val formatted = TransportErrorFormatter.describeFailure("Voice WebSocket", resolvedUrl, t, response)
                logger.debug("Ignoring stale Voice WS failure callback: {}", formatted.diagnosticMessage)
                return
            }
            connectInProgress = false
            this.webSocket = null
            isConnected = false
        }
        if (response?.code == 401 || response?.code == 403) {
            val authMessage = "Voice WebSocket upgrade rejected with HTTP ${response.code}"
            logger.warn("❌ {}. Attempting auth recovery.", authMessage)
            if (handleUnauthorizedFailure(authMessage)) {
                return
            }
        }

        val formatted = TransportErrorFormatter.describeFailure("Voice WebSocket", resolvedUrl, t, response)
        logger.error("❌ {}", formatted.diagnosticMessage, t)
        dispatchToUi { onStateChange("ERROR: ${formatted.userMessage}") }

        // Auto-reconnect on failure
        if (shouldReconnect) {
            scheduleReconnect()
        }
    }

    private fun handleUnauthorizedFailure(message: String): Boolean {
        synchronized(stateLock) {
            if (authRecoveryInProgress) {
                logger.info("Voice WS auth recovery already in progress; skipping duplicate recovery trigger")
                return true
            }
            authRecoveryInProgress = true
        }

        try {
            if (authRefreshAttempts < maxAuthRefreshAttempts && attemptTokenRefresh(message)) {
                authRefreshAttempts++
                logger.info("🔐 Voice WS token refresh succeeded, reconnecting immediately")
                dispatchToUi { onStateChange("Re-authenticating voice session...") }
                requestReconnect(300L, "auth recovery")
                return true
            }

            logger.warn("🔐 Voice WS auth recovery failed; moving voice channel to AUTH_REQUIRED")
            dispatchToUi { onStateChange("AUTH_REQUIRED: voice session expired, login required") }
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
            logger.warn("Cannot refresh Voice WS auth: refresh token missing ({})", reason)
            if (clearTokensOnFailure) {
                TokenManager.clearTokens()
            }
            return false
        }

        return try {
            logger.info("Refreshing Voice WS auth token ({})", reason)
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
                channel = "Voice auth refresh",
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

    private fun handleResolvedConfigChange(config: ResolvedDesktopConfig) {
        val normalizedLanguage = VoiceRecognitionLanguage.normalize(config.voiceLanguage)
        val endpointChanged = config.voiceWebSocketUrl != resolvedUrl
        val languageChanged = normalizedLanguage != currentVoiceLanguage

        if (languageChanged) {
            logger.info(
                "Voice recognition language updated from desktop config: {} -> {} (locale={})",
                currentVoiceLanguage,
                normalizedLanguage,
                config.locale.toLanguageTag()
            )
            currentVoiceLanguage = normalizedLanguage
        }

        if (endpointChanged) {
            handleEndpointChange(config.voiceWebSocketUrl)
            return
        }

        if (languageChanged && isConnected) {
            sendConfig(mapOf("language" to currentVoiceLanguage))
        }
    }
}
