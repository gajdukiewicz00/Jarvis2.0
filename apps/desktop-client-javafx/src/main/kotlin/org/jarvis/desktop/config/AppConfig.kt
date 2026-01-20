package org.jarvis.desktop.config

/**
 * Centralized application configuration for Desktop client.
 *
 * The API Gateway base URL can be overridden with:
 * - JARVIS_API_BASE_URL (preferred)
 * - API_URL (debug/port-forward)
 * WebSocket URLs are derived automatically to stay in sync with the REST base.
 */
object AppConfig {
    private const val DEFAULT_API_GATEWAY = "https://api.jarvis.local"

    val apiGatewayBaseUrl: String by lazy {
        val env = System.getenv("JARVIS_API_BASE_URL")
            ?: System.getenv("API_URL")
        val baseUrl = (env?.takeIf { it.isNotBlank() } ?: DEFAULT_API_GATEWAY).trimEnd('/')
        
        // Fail-fast: запрет "полу-TLS" режима
        val useTlsEnv = System.getenv("JARVIS_USE_TLS")?.toBoolean()
        val inferredTls = baseUrl.startsWith("https://") || baseUrl.contains("jarvis.local")
        val useTls = useTlsEnv ?: inferredTls
        val hasJarvisDomain = baseUrl.contains("jarvis.local")
        
        if (useTls && !baseUrl.startsWith("https://")) {
            throw IllegalStateException(
                "JARVIS_USE_TLS=true but API URL is not HTTPS: $baseUrl\n" +
                "TLS mode requires all external URLs to use https:// and wss://"
            )
        }
        
        if (!useTls && hasJarvisDomain) {
            throw IllegalStateException(
                "JARVIS_USE_TLS=false but API URL contains jarvis.local domain: $baseUrl\n" +
                "jarvis.local domains require TLS. Set JARVIS_USE_TLS=true or use IP/port URL"
            )
        }
        
        baseUrl
    }

    val apiBaseUrl: String by lazy {
        "$apiGatewayBaseUrl/api/v1"
    }

    val pcControlWebSocketUrl: String by lazy {
        System.getenv("JARVIS_PC_WS_URL")
            ?.takeIf { it.isNotBlank() }
            ?: "${wsBase()}/ws/pc-control"
    }

    val voiceWebSocketUrl: String by lazy {
        System.getenv("JARVIS_VOICE_WS_URL")
            ?.takeIf { it.isNotBlank() }
            ?: run {
                // If Iteration 7 (TLS) is active, use voice.jarvis.local with wss://
                val voiceDomain = System.getenv("JARVIS_VOICE_DOMAIN") ?: "voice.jarvis.local"
                val useTls = System.getenv("JARVIS_USE_TLS")?.toBoolean() ?: false
                
                if (useTls || apiGatewayBaseUrl.contains("api.jarvis.local")) {
                    "wss://$voiceDomain/ws/voice"
                } else {
                    "${wsBase()}/ws/voice"
                }
            }
    }

    private fun wsBase(): String {
        val base = apiGatewayBaseUrl
        // Support HTTPS/WSS when Iteration 7 (TLS) is active
        // If base URL is https://, use wss://; otherwise use ws://
        return when {
            base.startsWith("https://") -> base.replaceFirst("^https".toRegex(), "wss")
            base.startsWith("http://") -> base.replaceFirst("^http".toRegex(), "ws")
            else -> {
                // Fallback: if URL contains api.jarvis.local, assume HTTPS/WSS
                if (base.contains("api.jarvis.local") || base.contains("voice.jarvis.local")) {
                    base.replaceFirst("^http".toRegex(), "wss")
                } else {
                    base.replaceFirst("^http".toRegex(), "ws")
                }
            }
        }
    }
}

/**
 * Voice session configuration with sensible defaults.
 * All values can be overridden via environment variables.
 */
object VoiceConfig {
    /** Cooldown duration (ms) after TTS before re-enabling wake word (prevents echo/self-trigger) */
    val cooldownMs: Long by lazy {
        System.getenv("JARVIS_VOICE_COOLDOWN_MS")?.toLongOrNull() ?: 1500L
    }
    
    /** Maximum recording duration (ms) before auto-timeout */
    val listenTimeoutMs: Long by lazy {
        System.getenv("JARVIS_VOICE_LISTEN_TIMEOUT_MS")?.toLongOrNull() ?: 10000L
    }
    
    /** Timeout (ms) waiting for server response after sending end-of-speech */
    val processingTimeoutMs: Long by lazy {
        System.getenv("JARVIS_VOICE_PROCESSING_TIMEOUT_MS")?.toLongOrNull() ?: 15000L
    }
    
    /** Minimum transcript length to consider as a valid command (shorter = noise) */
    val minTranscriptLength: Int by lazy {
        System.getenv("JARVIS_VOICE_MIN_TRANSCRIPT_LENGTH")?.toIntOrNull() ?: 2
    }
    
    /** Enable noise filtering (ignore low-confidence / short transcripts) */
    val noiseFilteringEnabled: Boolean by lazy {
        System.getenv("JARVIS_VOICE_NOISE_FILTERING")?.toBoolean() ?: true
    }
    
    /** Known filler/noise words to ignore (space-separated in env var) */
    val noiseWords: Set<String> by lazy {
        val env = System.getenv("JARVIS_VOICE_NOISE_WORDS")
        if (env.isNullOrBlank()) {
            setOf("а", "э", "м", "эм", "хм", "ага", "угу", "ну", "так", "вот", "ой", "ах")
        } else {
            env.split(" ").filter { it.isNotBlank() }.toSet()
        }
    }
    
    /** Delay (ms) after wake word before starting recording (let wake word audio fade) */
    val wakeWordDelayMs: Long by lazy {
        System.getenv("JARVIS_VOICE_WAKE_WORD_DELAY_MS")?.toLongOrNull() ?: 300L
    }
}
