package org.jarvis.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSanitizerTest {

    @Test
    fun sanitize_masksSixteenDigitCardNumberKeepingLastFour() {
        val result = NotificationSanitizer.sanitize("Card 4111 1111 1111 1111 charged")

        assertTrue(result.contains("•••• 1111"))
        assertFalse(result.contains("4111 1111 1111"))
    }

    @Test
    fun sanitize_masksLongUnspacedAccountNumber() {
        val result = NotificationSanitizer.sanitize("Transfer to account 12345678901234")

        assertTrue(result.contains("•••• 1234"))
        assertFalse(result.contains("12345678901234"))
    }

    @Test
    fun sanitize_leavesShortMonetaryAmountUntouched() {
        val result = NotificationSanitizer.sanitize("Card payment of 42.50 PLN at Biedronka")

        assertEquals("Card payment of 42.50 PLN at Biedronka", result)
    }

    @Test
    fun sanitize_leavesGroupedAmountWithCommaUntouched() {
        val result = NotificationSanitizer.sanitize("Balance: 12 345,67 PLN")

        assertEquals("Balance: 12 345,67 PLN", result)
    }

    @Test
    fun sanitize_leavesShortDateUntouched() {
        val result = NotificationSanitizer.sanitize("Posted on 2026-07-04")

        assertEquals("Posted on 2026-07-04", result)
    }

    @Test
    fun sanitize_leavesPlainTextWithNoDigitsUntouched() {
        val result = NotificationSanitizer.sanitize("Your payment was successful")

        assertEquals("Your payment was successful", result)
    }

    @Test
    fun sanitize_handlesBlankInput() {
        assertEquals("", NotificationSanitizer.sanitize(""))
    }

    @Test
    fun sanitize_masksMultipleSensitiveRunsInSameText() {
        val result = NotificationSanitizer.sanitize("From 111122223333 to 444455556666")

        assertTrue(result.contains("•••• 3333"))
        assertTrue(result.contains("•••• 6666"))
    }
}
