package org.jarvis.desktop.config

import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

internal class DesktopConfigService(
    private val settingsStore: DesktopSettingsStore,
    private val environmentProvider: () -> Map<String, String> = System::getenv,
    private val localRuntimeEndpointProvider: () -> LocalRuntimeEndpointSnapshot? = LocalRuntimeEndpointDetector()::detectActive,
    private val runtimeSummaryFingerprintProvider: () -> String? = { null }
) {
    private val logger = LoggerFactory.getLogger(DesktopConfigService::class.java)

    private data class CachedResolvedDesktopConfig(
        val config: ResolvedDesktopConfig,
        val runtimeSummaryFingerprint: String?
    )

    private val listeners = CopyOnWriteArrayList<(ResolvedDesktopConfig) -> Unit>()
    private val stateLock = Any()
    private val current = AtomicReference(resolveCurrent())

    fun current(): ResolvedDesktopConfig {
        maybeRefreshAutoConfig()
        return current.get().config
    }

    fun reload(): ResolvedDesktopConfig = synchronized(stateLock) { updateCurrentLocked() }

    fun saveSettings(
        apiGatewayBaseUrl: String,
        locale: Locale,
        manualEndpointOverride: Boolean
    ): ResolvedDesktopConfig {
        settingsStore.save(
            DesktopConfigResolver.normalizePersistedSettings(
                apiGatewayBaseUrl = apiGatewayBaseUrl,
                locale = locale,
                manualEndpointOverride = manualEndpointOverride
            )
        )
        return synchronized(stateLock) { updateCurrentLocked() }
    }

    fun addListener(listener: (ResolvedDesktopConfig) -> Unit) {
        val resolved = current()
        listeners.add(listener)
        listener(resolved)
    }

    fun removeListener(listener: (ResolvedDesktopConfig) -> Unit) {
        listeners.remove(listener)
    }

    private fun maybeRefreshAutoConfig() {
        synchronized(stateLock) {
            val cached = current.get()
            if (cached.config.usesManualEndpointOverride) {
                return
            }

            val fingerprint = runtimeSummaryFingerprintProvider()
            if (fingerprint == cached.runtimeSummaryFingerprint) {
                return
            }

            updateCurrentLocked(autoRefresh = true)
        }
    }

    private fun updateCurrentLocked(autoRefresh: Boolean = false): ResolvedDesktopConfig {
        val resolved = resolveCurrent()
        val previous = current.getAndSet(resolved)
        if (resolved.config != previous.config) {
            if (autoRefresh) {
                logAutoRefresh(previous, resolved)
            }
            listeners.forEach { it(resolved.config) }
        }
        return resolved.config
    }

    private fun resolveCurrent(): CachedResolvedDesktopConfig {
        val resolvedConfig = DesktopConfigResolver.resolve(
            environment = environmentProvider(),
            settings = settingsStore.load(),
            localRuntimeEndpoint = localRuntimeEndpointProvider()
        )
        return CachedResolvedDesktopConfig(
            config = resolvedConfig,
            runtimeSummaryFingerprint = runtimeSummaryFingerprintProvider()
        )
    }

    private fun logAutoRefresh(
        previous: CachedResolvedDesktopConfig,
        resolved: CachedResolvedDesktopConfig
    ) {
        logger.info(
            "AUTO endpoint refresh applied: fingerprint={} -> {}, source={} -> {}, apiGatewayBaseUrl={} -> {}, voiceWebSocketUrl={} -> {}, pcControlWebSocketUrl={} -> {}, manualOverride={}",
            previous.runtimeSummaryFingerprint ?: "missing",
            resolved.runtimeSummaryFingerprint ?: "missing",
            previous.config.apiGatewaySource,
            resolved.config.apiGatewaySource,
            previous.config.apiGatewayBaseUrl,
            resolved.config.apiGatewayBaseUrl,
            previous.config.voiceWebSocketUrl,
            resolved.config.voiceWebSocketUrl,
            previous.config.pcControlWebSocketUrl,
            resolved.config.pcControlWebSocketUrl,
            resolved.config.usesManualEndpointOverride
        )
    }
}
