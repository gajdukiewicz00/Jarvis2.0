package org.jarvis.desktop.config

import java.util.prefs.Preferences

data class DesktopSettings(
    val apiGatewayBaseUrl: String? = null,
    val localeTag: String? = null,
    val endpointSelectionMode: EndpointSelectionMode? = null
)

interface DesktopSettingsStore {
    fun load(): DesktopSettings
    fun save(settings: DesktopSettings)
}

class PreferencesDesktopSettingsStore(
    private val preferences: Preferences = Preferences.userRoot().node("/org/jarvis/desktop/settings")
) : DesktopSettingsStore {

    override fun load(): DesktopSettings {
        return DesktopSettings(
            apiGatewayBaseUrl = normalizeUrl(preferences.get(KEY_API_GATEWAY_BASE_URL, null)),
            localeTag = preferences.get(KEY_LOCALE_TAG, null)?.trim()?.takeIf { it.isNotEmpty() },
            endpointSelectionMode = EndpointSelectionMode.fromPersisted(
                preferences.get(KEY_ENDPOINT_SELECTION_MODE, null)
            )
        )
    }

    override fun save(settings: DesktopSettings) {
        putOrRemove(KEY_API_GATEWAY_BASE_URL, normalizeUrl(settings.apiGatewayBaseUrl))
        putOrRemove(KEY_LOCALE_TAG, settings.localeTag?.trim()?.takeIf { it.isNotEmpty() })
        putOrRemove(KEY_ENDPOINT_SELECTION_MODE, settings.endpointSelectionMode?.name)
        preferences.flush()
    }

    private fun putOrRemove(key: String, value: String?) {
        if (value == null) {
            preferences.remove(key)
        } else {
            preferences.put(key, value)
        }
    }

    private fun normalizeUrl(url: String?): String? {
        return url?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val KEY_API_GATEWAY_BASE_URL = "api_gateway_base_url"
        const val KEY_LOCALE_TAG = "locale_tag"
        const val KEY_ENDPOINT_SELECTION_MODE = "endpoint_selection_mode"
    }
}
