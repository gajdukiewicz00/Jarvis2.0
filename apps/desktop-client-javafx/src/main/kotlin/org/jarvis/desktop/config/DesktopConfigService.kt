package org.jarvis.desktop.config

import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

internal class DesktopConfigService(
    private val settingsStore: DesktopSettingsStore,
    private val environmentProvider: () -> Map<String, String> = System::getenv,
    private val localRuntimeEndpointProvider: () -> LocalRuntimeEndpointSnapshot? = LocalRuntimeEndpointDetector()::detectActive
) {
    private val listeners = CopyOnWriteArrayList<(ResolvedDesktopConfig) -> Unit>()
    private val current = AtomicReference(resolveCurrent())

    fun current(): ResolvedDesktopConfig = current.get()

    fun reload(): ResolvedDesktopConfig = updateCurrent()

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
        return updateCurrent()
    }

    fun addListener(listener: (ResolvedDesktopConfig) -> Unit) {
        listeners.add(listener)
        listener(current())
    }

    fun removeListener(listener: (ResolvedDesktopConfig) -> Unit) {
        listeners.remove(listener)
    }

    private fun updateCurrent(): ResolvedDesktopConfig {
        val resolved = resolveCurrent()
        current.set(resolved)
        listeners.forEach { it(resolved) }
        return resolved
    }

    private fun resolveCurrent(): ResolvedDesktopConfig {
        return DesktopConfigResolver.resolve(
            environment = environmentProvider(),
            settings = settingsStore.load(),
            localRuntimeEndpoint = localRuntimeEndpointProvider()
        )
    }
}
