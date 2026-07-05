package org.jarvis.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [BankAppSettings] itself needs a [android.content.Context] to construct, but the
 * retention-days clamping rule it delegates to is a top-level pure function — tested
 * directly here without any Android dependency.
 */
class BankAppSettingsClampTest {

    @Test
    fun clampRetentionDays_leavesValueWithinRangeUnchanged() {
        assertEquals(7, clampRetentionDays(7))
    }

    @Test
    fun clampRetentionDays_clampsNegativeValuesToZero() {
        assertEquals(0, clampRetentionDays(-5))
    }

    @Test
    fun clampRetentionDays_clampsValuesAboveMaxDown() {
        assertEquals(MAX_BANK_DRAFT_RETENTION_DAYS, clampRetentionDays(9999))
    }

    @Test
    fun clampRetentionDays_allowsZeroAsDoNotStoreRawText() {
        assertEquals(0, clampRetentionDays(0))
    }

    @Test
    fun clampRetentionDays_allowsExactMaxBoundary() {
        assertEquals(MAX_BANK_DRAFT_RETENTION_DAYS, clampRetentionDays(MAX_BANK_DRAFT_RETENTION_DAYS))
    }
}
