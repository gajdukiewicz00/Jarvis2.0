package org.jarvis.desktop.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.prefs.Preferences

class PreferencesDesktopSettingsStoreTest {

    @Test
    fun saveAndLoadRoundTrip() {
        val preferences = Preferences.userRoot().node("/org/jarvis/desktop/test/${UUID.randomUUID()}")
        try {
            val store = PreferencesDesktopSettingsStore(preferences)

            store.save(
                DesktopSettings(
                    apiGatewayBaseUrl = "http://localhost:8080/",
                    localeTag = "pl-PL"
                )
            )

            assertEquals(
                DesktopSettings(
                    apiGatewayBaseUrl = "http://localhost:8080",
                    localeTag = "pl-PL"
                ),
                store.load()
            )
        } finally {
            preferences.removeNode()
            preferences.flush()
        }
    }
}
