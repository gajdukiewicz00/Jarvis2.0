package org.jarvis.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Increment #12 (privacy hardening) — secure-by-default coverage for
 * [BankAppSettings]'s fresh-install defaults.
 *
 * <p>[BankAppSettings] itself needs a live [android.content.Context]/`SharedPreferences`
 * to construct (see [BankAppSettingsClampTest]'s note), which this JVM unit-test module
 * cannot provide. The default *values* it falls back to (capture off, empty whitelist,
 * zero-day retention) are extracted as top-level constants for exactly this reason —
 * this test exercises those constants directly, plus the observable behavior they drive
 * ([BankNotificationFilter.isWhitelisted], `storeRawText`) so a regression back to the
 * old opt-out defaults (capture on, every known bank whitelisted, 7-day retention) would
 * fail these tests even without a live [BankAppSettings] instance.</p>
 */
class BankAppSettingsDefaultsTest {

    @Test
    fun defaultCaptureEnabled_isFalse() {
        assertFalse(DEFAULT_BANK_NOTIFICATION_CAPTURE_ENABLED)
    }

    @Test
    fun defaultEnabledPackages_isEmpty() {
        assertTrue(DEFAULT_BANK_ENABLED_PACKAGES.isEmpty())
    }

    @Test
    fun defaultRetentionDays_isZero() {
        assertEquals(0, DEFAULT_BANK_DRAFT_RETENTION_DAYS)
    }

    @Test
    fun defaultEnabledPackages_whitelistsNoKnownBankPackage() {
        BankAppRegistry.KNOWN_BANK_APPS.forEach { bank ->
            assertFalse(
                "expected ${bank.packageName} NOT to be captured by default",
                BankNotificationFilter.isWhitelisted(bank.packageName, DEFAULT_BANK_ENABLED_PACKAGES)
            )
        }
    }

    @Test
    fun defaultRetentionDays_meansRawTextIsNeverStoredByDefault() {
        // Mirrors BankNotificationListenerService.handlePosted's `storeRawText = retentionDays > 0`.
        val storeRawTextByDefault = DEFAULT_BANK_DRAFT_RETENTION_DAYS > 0

        assertFalse(storeRawTextByDefault)
    }
}
