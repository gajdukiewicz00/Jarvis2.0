package org.jarvis.android.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class BankDraftRetentionTest {

    @Test
    fun isBankNotificationDraft_trueWhenSourceMatches() {
        val payload = """{"source":"BANK_NOTIFICATION","bankName":"mBank"}"""

        assertTrue(BankDraftRetention.isBankNotificationDraft(payload))
    }

    @Test
    fun isBankNotificationDraft_falseForManualFinanceEntry() {
        val payload = """{"amount":42.5,"currency":"EUR","type":"EXPENSE"}"""

        assertFalse(BankDraftRetention.isBankNotificationDraft(payload))
    }

    @Test
    fun isBankNotificationDraft_falseForMalformedJson() {
        assertFalse(BankDraftRetention.isBankNotificationDraft("not json at all"))
    }

    @Test
    fun isExpired_trueWhenRetentionDaysIsZero() {
        assertTrue(BankDraftRetention.isExpired(createdAtEpochMs = 0L, nowEpochMs = 0L, retentionDays = 0))
    }

    @Test
    fun isExpired_trueWhenOlderThanRetentionWindow() {
        val now = 1_000_000_000L
        val eightDaysMs = TimeUnit.DAYS.toMillis(8)

        assertTrue(BankDraftRetention.isExpired(now - eightDaysMs, now, retentionDays = 7))
    }

    @Test
    fun isExpired_falseWhenWithinRetentionWindow() {
        val now = 1_000_000_000L
        val oneDayMs = TimeUnit.DAYS.toMillis(1)

        assertFalse(BankDraftRetention.isExpired(now - oneDayMs, now, retentionDays = 7))
    }

    @Test
    fun isExpired_trueExactlyAtBoundary() {
        val now = 1_000_000_000L
        val sevenDaysMs = TimeUnit.DAYS.toMillis(7)

        assertTrue(BankDraftRetention.isExpired(now - sevenDaysMs, now, retentionDays = 7))
    }
}
