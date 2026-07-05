package org.jarvis.desktop.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

class ThemePreferenceTest {

    private fun isolatedStore(): Pair<ThemePreferenceStore, Preferences> {
        val preferences = Preferences.userRoot().node("/org/jarvis/desktop/test/${UUID.randomUUID()}")
        return ThemePreferenceStore(preferences) to preferences
    }

    @Test
    fun `defaults to disabled`() {
        val (store, preferences) = isolatedStore()
        try {
            assertFalse(store.isStarkLabEnabled())
        } finally {
            preferences.removeNode()
            preferences.flush()
        }
    }

    @Test
    fun `setStarkLabEnabled persists the value`() {
        val (store, preferences) = isolatedStore()
        try {
            store.setStarkLabEnabled(true)
            assertTrue(store.isStarkLabEnabled())

            store.setStarkLabEnabled(false)
            assertFalse(store.isStarkLabEnabled())
        } finally {
            preferences.removeNode()
            preferences.flush()
        }
    }

    @Test
    fun `listener fires with the new value when the preference changes`() {
        val (store, preferences) = isolatedStore()
        try {
            val latch = CountDownLatch(1)
            var observed: Boolean? = null
            val listener = store.addListener { enabled ->
                observed = enabled
                latch.countDown()
            }

            try {
                store.setStarkLabEnabled(true)
                assertTrue(latch.await(5, TimeUnit.SECONDS))
                assertEquals(true, observed)
            } finally {
                store.removeListener(listener)
            }
        } finally {
            preferences.removeNode()
            preferences.flush()
        }
    }
}
