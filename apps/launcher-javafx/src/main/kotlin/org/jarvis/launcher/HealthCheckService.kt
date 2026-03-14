package org.jarvis.launcher

import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Health check service for Jarvis backend services.
 * Performs multi-signal health checks:
 * - Process-level: PID alive?
 * - Network-level: API endpoint accessible?
 * - Service-level: Health endpoints return UP
 * 
 * Uses hysteresis to prevent UI "jitter":
 * - READY requires 2 consecutive successful checks
 * - ERROR requires 3 consecutive failed checks
 */
class HealthCheckService(
    private val apiBaseUrl: String,
    private val kubeconfigProvider: () -> String?,
    private val onStatusChange: (ServiceHealthStatus) -> Unit
) {
    private val logger = LoggerFactory.getLogger(HealthCheckService::class.java)
    
    private val currentStatus = AtomicReference<ServiceHealthStatus>(
        ServiceHealthStatus(
            overall = ServiceHealthStatus.OverallStatus.IDLE,
            coreServices = emptyMap(),
            optionalServices = emptyMap()
        )
    )
    private val consecutiveSuccess = AtomicInteger(0)
    private val consecutiveFailures = AtomicInteger(0)
    private val runningCheck = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var llmEnabled = false
    @Volatile private var memoryEnabled = false
    @Volatile private var voiceEnabled = true
    @Volatile private var voiceRequired = false
    @Volatile private var cachedTruststorePath: String? = null
    @Volatile private var cachedSslSocketFactory: javax.net.ssl.SSLSocketFactory? = null
    private val localRuntime =
        apiBaseUrl.contains("127.0.0.1") ||
            apiBaseUrl.contains("localhost") ||
            (System.getenv("JARVIS_RUNTIME_MODE")?.equals("local", ignoreCase = true) == true)

    fun updateFlags(
        llmEnabled: Boolean,
        memoryEnabled: Boolean,
        voiceEnabled: Boolean = true,
        voiceRequired: Boolean = false
    ) {
        this.llmEnabled = llmEnabled
        this.memoryEnabled = memoryEnabled
        this.voiceEnabled = voiceEnabled
        this.voiceRequired = voiceRequired
    }
    
    /**
     * Service health status with detailed information.
     */
    data class ServiceHealthStatus(
        val overall: OverallStatus,
        val coreServices: Map<String, ServiceCheck>,
        val optionalServices: Map<String, ServiceCheck>,
        val reasons: List<String> = emptyList()
    ) {
        enum class OverallStatus {
            IDLE,           // Backend not started
            STARTING,       // Backend starting, checks not OK yet
            READY,          // All core services UP
            DEGRADED,       // Core UP, but optional services down
            ERROR           // Core services down or fatal error
        }
        
        data class ServiceCheck(
            val name: String,
            val status: CheckStatus,
            val message: String,
            val lastChecked: Long = System.currentTimeMillis(),
            val isDisabled: Boolean = false  // true if service is intentionally disabled (not an error)
        ) {
            enum class CheckStatus {
                UP, DOWN, UNKNOWN  // UNKNOWN can be disabled (ok) or error (fail)
            }
        }
    }
    
    /**
     * Perform health check cycle.
     * Returns current status (may not change immediately due to hysteresis).
     * Thread-safe: prevents concurrent checks.
     * Includes watchdog timeout to prevent guard from getting stuck.
     */
    fun checkHealth(backendPid: Long?, backendExpectedRunning: Boolean = false): ServiceHealthStatus? {
        // Prevent concurrent checks
        if (!runningCheck.compareAndSet(false, true)) {
            // Another check is running, return current status
            return currentStatus.get()
        }
        
        // Watchdog timeout: ensure guard is released even if check hangs
        val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        val watchdogTask = watchdog.schedule({
            logger.warn("Health check watchdog timeout (4s), releasing guard")
            runningCheck.set(false)
        }, 4, java.util.concurrent.TimeUnit.SECONDS)
        
        return try {
            checkHealthInternal(backendPid, backendExpectedRunning)
        } catch (e: Exception) {
            logger.error("Health check error", e)
            currentStatus.get()  // Return current status on error
        } finally {
            watchdogTask.cancel(false)
            watchdog.shutdown()
            // Always release guard, even if check hung
            runningCheck.set(false)
        }
    }
    
    private fun checkHealthInternal(backendPid: Long?, backendExpectedRunning: Boolean): ServiceHealthStatus {
        val processAlive = backendPid != null && isProcessAlive(backendPid)
        val requiredServices = mutableListOf("api-gateway", "security-service")
        val optionalServices = mutableListOf("llm-service", "memory-service")
        if (voiceRequired) {
            requiredServices.add("voice-gateway")
        } else {
            optionalServices.add(0, "voice-gateway")
        }

        val coreChecks = linkedMapOf<String, ServiceHealthStatus.ServiceCheck>()
        val apiCheck = checkApiGateway()
        val externallyObservable = apiCheck.status != ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN

        // IDLE: no bootstrap process and nothing externally reachable.
        if (!processAlive && !backendExpectedRunning && !externallyObservable) {
            consecutiveSuccess.set(0)
            consecutiveFailures.set(0)
            val status = ServiceHealthStatus(
                overall = ServiceHealthStatus.OverallStatus.IDLE,
                coreServices = emptyMap(),
                optionalServices = emptyMap(),
                reasons = listOf("Backend not running")
            )
            updateStatus(status)
            return status
        }

        requiredServices.forEach { serviceName ->
            coreChecks[serviceName] = if (serviceName == "api-gateway") {
                apiCheck
            } else {
                checkServiceHealth(serviceName)
            }
        }

        val optionalChecks = optionalServices.associateWith { serviceName ->
            checkServiceHealth(serviceName, optional = true)
        }
        
        // Determine core and optional readiness
        val coreReady = coreChecks.values.all { it.status == ServiceHealthStatus.ServiceCheck.CheckStatus.UP }
        val coreDown = coreChecks.values.any { it.status == ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN }
        // Optional ready: UP or UNKNOWN only if disabled (not error/timeout)
        val optionalReady = optionalChecks.values.all { check ->
            when (check.status) {
                ServiceHealthStatus.ServiceCheck.CheckStatus.UP -> true
                ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN -> check.isDisabled  // Only disabled UNKNOWN is OK
                ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN -> false
            }
        }
        val optionalDown = optionalChecks.values.any { 
            it.status == ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN || 
            (it.status == ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN && !it.isDisabled)  // UNKNOWN error is treated as DOWN
        }
        
        // Build detailed reasons
        val reasons = mutableListOf<String>()
        reasons.add(
            "Bootstrap process: ${
                when {
                    processAlive -> "alive"
                    backendExpectedRunning -> "finished"
                    externallyObservable -> "not-running (backend reachable)"
                    else -> "dead"
                }
            }"
        )
        coreChecks.forEach { (name, check) ->
            val statusStr = when (check.status) {
                ServiceHealthStatus.ServiceCheck.CheckStatus.UP -> "UP"
                ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN -> "DOWN"
                ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN -> "UNKNOWN"
            }
            reasons.add("Core $name: $statusStr (${check.message})")
        }
        optionalChecks.forEach { (name, check) ->
            if (check.status != ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN) {
                val statusStr = when (check.status) {
                    ServiceHealthStatus.ServiceCheck.CheckStatus.UP -> "OK"
                    ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN -> "FAIL"
                    ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN -> "UNKNOWN"
                }
                reasons.add("Optional $name: $statusStr (${check.message})")
            }
        }
        
        val newStatus = when {
            coreReady -> {
                // Hysteresis: require 2 consecutive successes for READY
                if (consecutiveSuccess.incrementAndGet() >= 2) {
                    consecutiveFailures.set(0)
                    // READY if all optional are ready, DEGRADED if some are down
                    if (optionalDown && !optionalReady) {
                        ServiceHealthStatus.OverallStatus.DEGRADED
                    } else {
                        ServiceHealthStatus.OverallStatus.READY
                    }
                } else {
                    // Still transitioning to READY
                    ServiceHealthStatus.OverallStatus.STARTING
                }
            }
            coreDown -> {
                // Hysteresis: require 3 consecutive failures for ERROR
                if (consecutiveFailures.incrementAndGet() >= 3) {
                    consecutiveSuccess.set(0)
                    ServiceHealthStatus.OverallStatus.ERROR
                } else {
                    // Still in STARTING, might recover
                    ServiceHealthStatus.OverallStatus.STARTING
                }
            }
            else -> {
                consecutiveFailures.incrementAndGet()
                consecutiveSuccess.set(0)
                ServiceHealthStatus.OverallStatus.STARTING
            }
        }
        
        val status = ServiceHealthStatus(
            overall = newStatus,
            coreServices = coreChecks,
            optionalServices = optionalChecks,
            reasons = reasons
        )
        
        updateStatus(status)
        return status
    }
    
    private fun checkServiceHealth(serviceName: String, optional: Boolean = false): ServiceHealthStatus.ServiceCheck {
        return try {
            when (serviceName) {
                "api-gateway" -> checkApiGateway()
                "security-service" -> checkSecurityService()
                "voice-gateway" -> checkVoiceGateway(optional)
                "llm-service" -> checkLlmService(optional)
                "memory-service" -> checkMemoryService(optional)
                else -> ServiceHealthStatus.ServiceCheck(
                    name = serviceName,
                    status = ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN,
                    message = "Unknown service"
                )
            }
        } catch (e: Exception) {
            logger.warn("Health check failed for $serviceName", e)
            ServiceHealthStatus.ServiceCheck(
                name = serviceName,
                status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                message = "Error: ${e.message}"
            )
        }
    }
    
    private fun checkApiGateway(): ServiceHealthStatus.ServiceCheck {
        return try {
            // Iteration 1.5 (Stage 7): Support HTTPS for api.jarvis.local
            val healthUrl = "$apiBaseUrl/actuator/health"
            val connection = openConnection(URL(healthUrl))
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.requestMethod = "GET"
            
            // For HTTPS, Java uses system trust store by default (includes our CA after installation)
            connection.connect()
            
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                if (response.contains("\"status\":\"UP\"") || response.contains("UP")) {
                    ServiceHealthStatus.ServiceCheck(
                        name = "api-gateway",
                        status = ServiceHealthStatus.ServiceCheck.CheckStatus.UP,
                        message = "UP"
                    )
                } else {
                    ServiceHealthStatus.ServiceCheck(
                        name = "api-gateway",
                        status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                        message = "Status not UP: $response"
                    )
                }
            } else {
                ServiceHealthStatus.ServiceCheck(
                    name = "api-gateway",
                    status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                    message = "HTTP $responseCode"
                )
            }
        } catch (e: javax.net.ssl.SSLException) {
            val msg = e.message?.take(120) ?: "SSL error"
            val isTrust = msg.contains("PKIX", ignoreCase = true) ||
                msg.contains("certificate_unknown", ignoreCase = true) ||
                msg.contains("unable to find valid certification path", ignoreCase = true)
            ServiceHealthStatus.ServiceCheck(
                name = "api-gateway",
                status = if (isTrust) ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN
                else ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                message = if (isTrust) "TLS trust missing - click Fix TLS" else "SSL error: ${msg.take(50)}"
            )
        } catch (e: java.net.ConnectException) {
            ServiceHealthStatus.ServiceCheck(
                name = "api-gateway",
                status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                message = "Connection refused: ${e.message}"
            )
        } catch (e: Exception) {
            ServiceHealthStatus.ServiceCheck(
                name = "api-gateway",
                status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                message = "Error: ${e.message?.take(50) ?: "Unknown"}"
            )
        }
    }
    
    private fun checkSecurityService(): ServiceHealthStatus.ServiceCheck {
        if (localRuntime) {
            return checkHttpService("security-service", "http://127.0.0.1:8088/actuator/health")
        }
        // Check security-service through api-gateway or directly
        return try {
            // Try through gateway first
            val healthUrl = "$apiBaseUrl/actuator/health"
            val connection = openConnection(URL(healthUrl))
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.requestMethod = "GET"
            
            // Iteration 1.5 (Stage 7): Support HTTPS
            connection.connect()
            
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                // Check if security-service components are UP
                // Gateway health should include downstream services
                if (response.contains("\"status\":\"UP\"") || response.contains("UP")) {
                    // If gateway is UP, assume security-service is accessible through it
                    // (Gateway won't be UP if security-service is down)
                    ServiceHealthStatus.ServiceCheck(
                        name = "security-service",
                        status = ServiceHealthStatus.ServiceCheck.CheckStatus.UP,
                        message = "UP (via gateway)"
                    )
                } else {
                    ServiceHealthStatus.ServiceCheck(
                        name = "security-service",
                        status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                        message = "Status not UP"
                    )
                }
            } else {
                ServiceHealthStatus.ServiceCheck(
                    name = "security-service",
                    status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                    message = "Gateway returned $responseCode"
                )
            }
        } catch (e: javax.net.ssl.SSLException) {
            val msg = e.message?.take(120) ?: "SSL error"
            val isTrust = msg.contains("PKIX", ignoreCase = true) ||
                msg.contains("certificate_unknown", ignoreCase = true) ||
                msg.contains("unable to find valid certification path", ignoreCase = true)
            ServiceHealthStatus.ServiceCheck(
                name = "security-service",
                status = if (isTrust) ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN
                else ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                message = if (isTrust) "TLS trust missing - click Fix TLS" else "SSL error: ${msg.take(50)}"
            )
        } catch (e: Exception) {
            ServiceHealthStatus.ServiceCheck(
                name = "security-service",
                status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                message = "Connection error: ${e.message?.take(50) ?: "Unknown"}"
            )
        }
    }
    
    private fun checkVoiceGateway(optional: Boolean): ServiceHealthStatus.ServiceCheck {
        // Voice gateway is optional, check if disabled by flag
        if (!voiceEnabled) {
            return ServiceHealthStatus.ServiceCheck(
                name = "voice-gateway",
                status = ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN,
                message = "Disabled by flag",
                isDisabled = true  // Mark as intentionally disabled
            )
        }

        if (localRuntime) {
            return checkHttpService("voice-gateway", "http://127.0.0.1:8081/actuator/health")
        }

        // voice.jarvis.local is routed through api-gateway ingress, so HTTP /actuator checks
        // are not authoritative for the voice-gateway deployment itself.
        return checkWorkloadHealth("deployment", "voice-gateway")
    }

    private fun checkWorkloadHealth(kind: String, name: String): ServiceHealthStatus.ServiceCheck {
        return try {
            val desiredText = kubectlJsonPath(kind, name, "{.spec.replicas}")
            val desired = desiredText.toIntOrNull()
            if (desired == null) {
                return ServiceHealthStatus.ServiceCheck(
                    name = name,
                    status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                    message = "kubectl returned invalid desired replicas"
                )
            }

            val ready = kubectlJsonPath(kind, name, "{.status.readyReplicas}").toIntOrNull() ?: 0
            val available = when (kind) {
                "deployment" -> kubectlJsonPath(kind, name, "{.status.availableReplicas}").toIntOrNull() ?: 0
                else -> ready
            }

            if (desired == 0) {
                return ServiceHealthStatus.ServiceCheck(
                    name = name,
                    status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                    message = "Scaled to 0 replicas"
                )
            }

            if (ready >= desired && available >= desired) {
                ServiceHealthStatus.ServiceCheck(
                    name = name,
                    status = ServiceHealthStatus.ServiceCheck.CheckStatus.UP,
                    message = "Ready $ready/$desired"
                )
            } else {
                ServiceHealthStatus.ServiceCheck(
                    name = name,
                    status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                    message = "Ready $ready/$desired, available $available/$desired"
                )
            }
        } catch (e: java.net.SocketTimeoutException) {
            ServiceHealthStatus.ServiceCheck(
                name = name,
                status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                message = "kubectl timeout"
            )
        } catch (e: Exception) {
            ServiceHealthStatus.ServiceCheck(
                name = name,
                status = ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN,
                message = "kubectl unavailable: ${e.message?.take(50) ?: "Unknown"}"
            )
        }
    }

    private fun kubectlJsonPath(kind: String, name: String, path: String): String {
        val command = mutableListOf("kubectl")
        val kubeconfig = kubeconfigProvider()?.takeIf { it.isNotBlank() }
        if (kubeconfig != null) {
            command += listOf("--kubeconfig", kubeconfig)
        }
        command += listOf("get", kind, name, "-n", "jarvis", "-o", "jsonpath=$path")

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw java.net.SocketTimeoutException("kubectl get $kind/$name timed out")
        }

        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        if (process.exitValue() != 0) {
            throw IllegalStateException(output.ifBlank { "kubectl get $kind/$name failed" })
        }

        return output
    }
    
    private fun checkLlmService(optional: Boolean): ServiceHealthStatus.ServiceCheck {
        // LLM service is optional, check if disabled by flag
        if (!llmEnabled) {
            return ServiceHealthStatus.ServiceCheck(
                name = "llm-service",
                status = ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN,
                message = "Disabled by flag (ENABLE_LLM=false)",
                isDisabled = true  // Mark as intentionally disabled
            )
        }

        if (localRuntime) {
            return checkHttpService("llm-service", "http://127.0.0.1:8091/api/v1/llm/health")
        }
        
        // LLM enabled - perform actual health check
        return try {
            // LLM service health endpoint via API Gateway
            val healthUrl = "$apiBaseUrl/actuator/health"
            val connection = openConnection(URL(healthUrl))
            
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.setRequestProperty("Accept", "application/json")
            
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                // Check if LLM components are UP in health response
                if (response.contains("\"llm-service\"") || response.contains("\"llm-server\"") || 
                    response.contains("\"status\":\"UP\"")) {
                    ServiceHealthStatus.ServiceCheck(
                        name = "llm-service",
                        status = ServiceHealthStatus.ServiceCheck.CheckStatus.UP,
                        message = "UP"
                    )
                } else {
                    // Try direct LLM service check
                    checkLlmServiceDirect()
                }
            } else {
                checkLlmServiceDirect()
            }
        } catch (e: javax.net.ssl.SSLException) {
            ServiceHealthStatus.ServiceCheck(
                name = "llm-service",
                status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                message = "SSL error: ${e.message?.take(50)}"
            )
        } catch (e: java.net.SocketTimeoutException) {
            ServiceHealthStatus.ServiceCheck(
                name = "llm-service",
                status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                message = "Timeout (service may be starting or GPU unavailable)"
            )
        } catch (e: java.net.ConnectException) {
            ServiceHealthStatus.ServiceCheck(
                name = "llm-service",
                status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                message = "Connection refused (service not ready or GPU missing)"
            )
        } catch (e: Exception) {
            ServiceHealthStatus.ServiceCheck(
                name = "llm-service",
                status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                message = "Error: ${e.message?.take(50) ?: "Unknown"}"
            )
        }
    }
    
    private fun checkLlmServiceDirect(): ServiceHealthStatus.ServiceCheck {
        // Fallback: try to check LLM service directly or via kubectl
        // For now, if API Gateway check fails, assume DOWN
        return ServiceHealthStatus.ServiceCheck(
            name = "llm-service",
            status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
            message = "Not accessible via API Gateway (check GPU prerequisites or pod logs)"
        )
    }
    
    private fun checkMemoryService(optional: Boolean): ServiceHealthStatus.ServiceCheck {
        // Memory service is optional, check if disabled by flag
        if (!memoryEnabled) {
            return ServiceHealthStatus.ServiceCheck(
                name = "memory-service",
                status = ServiceHealthStatus.ServiceCheck.CheckStatus.UNKNOWN,
                message = "Disabled by flag (ENABLE_MEMORY=false)",
                isDisabled = true  // Mark as intentionally disabled
            )
        }

        if (localRuntime) {
            return checkHttpService("memory-service", "http://127.0.0.1:8093/actuator/health")
        }
        
        // Memory enabled - perform actual health check
        return try {
            // Memory service health endpoint: /actuator/health (Spring Boot) or /memory/health
            val healthUrl = "$apiBaseUrl/actuator/health"  // Try via API Gateway first
            val connection = openConnection(URL(healthUrl))
            
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.setRequestProperty("Accept", "application/json")
            
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                // Try to parse health response
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                if (response.contains("\"memory-service\"") || response.contains("\"status\":\"UP\"")) {
                    ServiceHealthStatus.ServiceCheck(
                        name = "memory-service",
                        status = ServiceHealthStatus.ServiceCheck.CheckStatus.UP,
                        message = "UP"
                    )
                } else {
                    // Fallback: check direct memory-service endpoint
                    checkMemoryServiceDirect()
                }
            } else {
                // Fallback: check direct memory-service endpoint
                checkMemoryServiceDirect()
            }
        } catch (e: javax.net.ssl.SSLException) {
            ServiceHealthStatus.ServiceCheck(
                name = "memory-service",
                status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                message = "SSL error: ${e.message?.take(50)}"
            )
        } catch (e: java.net.SocketTimeoutException) {
            ServiceHealthStatus.ServiceCheck(
                name = "memory-service",
                status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                message = "Timeout (service may be starting)"
            )
        } catch (e: java.net.ConnectException) {
            ServiceHealthStatus.ServiceCheck(
                name = "memory-service",
                status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                message = "Connection refused (service not ready)"
            )
        } catch (e: Exception) {
            ServiceHealthStatus.ServiceCheck(
                name = "memory-service",
                status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                message = "Error: ${e.message?.take(50) ?: "Unknown"}"
            )
        }
    }
    
    private fun checkMemoryServiceDirect(): ServiceHealthStatus.ServiceCheck {
        // Fallback: try direct memory-service endpoint (if accessible)
        return try {
            // Try to check via kubectl or direct service URL
            // For now, if API Gateway check fails, assume DOWN (service may not be routed)
            ServiceHealthStatus.ServiceCheck(
                name = "memory-service",
                status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                message = "Not accessible via API Gateway"
            )
        } catch (e: Exception) {
            ServiceHealthStatus.ServiceCheck(
                name = "memory-service",
                status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                message = "Health check failed: ${e.message?.take(50) ?: "Unknown"}"
            )
        }
    }
    
    private fun isProcessAlive(pid: Long): Boolean {
        return try {
            java.lang.ProcessHandle.of(pid)
                .map { it.isAlive }
                .orElse(false)
        } catch (e: Exception) {
            false
        }
    }
    
    private fun updateStatus(newStatus: ServiceHealthStatus) {
        val oldStatus = currentStatus.get()
        if (oldStatus.overall != newStatus.overall) {
            logger.info("Health status changed: ${oldStatus.overall} -> ${newStatus.overall}")
            currentStatus.set(newStatus)
            onStatusChange(newStatus)
        } else {
            // Update status even if overall didn't change (for detailed info)
            currentStatus.set(newStatus)
        }
    }
    
    fun getCurrentStatus(): ServiceHealthStatus? {
        return currentStatus.get()
    }

    private fun checkHttpService(name: String, url: String): ServiceHealthStatus.ServiceCheck {
        return try {
            val connection = openConnection(URL(url))
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.connect()

            if (connection.responseCode == 200) {
                ServiceHealthStatus.ServiceCheck(
                    name = name,
                    status = ServiceHealthStatus.ServiceCheck.CheckStatus.UP,
                    message = "UP"
                )
            } else {
                ServiceHealthStatus.ServiceCheck(
                    name = name,
                    status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                    message = "HTTP ${connection.responseCode}"
                )
            }
        } catch (e: Exception) {
            ServiceHealthStatus.ServiceCheck(
                name = name,
                status = ServiceHealthStatus.ServiceCheck.CheckStatus.DOWN,
                message = "Error: ${e.message?.take(50) ?: "Unknown"}"
            )
        }
    }

    private fun openConnection(url: URL): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection) {
            val socketFactory = resolveSslSocketFactory()
            if (socketFactory != null) {
                connection.sslSocketFactory = socketFactory
            }
        }
        return connection
    }

    private fun resolveSslSocketFactory(): javax.net.ssl.SSLSocketFactory? {
        val truststorePath = resolveTruststorePath() ?: return null
        if (truststorePath == cachedTruststorePath && cachedSslSocketFactory != null) {
            return cachedSslSocketFactory
        }
        return try {
            val pass = System.getenv("JARVIS_JAVA_TRUSTSTORE_PASSWORD") ?: "changeit"
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            FileInputStream(truststorePath).use { input ->
                keyStore.load(input, pass.toCharArray())
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, tmf.trustManagers, null)
            cachedTruststorePath = truststorePath
            cachedSslSocketFactory = sslContext.socketFactory
            cachedSslSocketFactory
        } catch (e: Exception) {
            logger.warn("Failed to load truststore at $truststorePath", e)
            null
        }
    }

    private fun resolveTruststorePath(): String? {
        val envPath = System.getenv("JARVIS_JAVA_TRUSTSTORE")
        if (!envPath.isNullOrBlank() && Files.exists(Paths.get(envPath))) {
            return envPath
        }
        val userPath = Paths.get(System.getProperty("user.home"), ".jarvis", "tls", "jarvis-cacerts.jks")
        if (Files.exists(userPath)) {
            return userPath.toString()
        }
        return null
    }
}
