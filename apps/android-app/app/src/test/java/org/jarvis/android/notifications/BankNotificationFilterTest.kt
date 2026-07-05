package org.jarvis.android.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BankNotificationFilterTest {

    @Test
    fun isWhitelisted_trueForPackageInEnabledSet() {
        val enabled = setOf("pl.mbank", "com.revolut.revolut")

        assertTrue(BankNotificationFilter.isWhitelisted("pl.mbank", enabled))
    }

    @Test
    fun isWhitelisted_falseForPackageNotInEnabledSet() {
        val enabled = setOf("pl.mbank")

        assertFalse(BankNotificationFilter.isWhitelisted("com.some.randomapp", enabled))
    }

    @Test
    fun isWhitelisted_falseForEmptyEnabledSet() {
        assertFalse(BankNotificationFilter.isWhitelisted("pl.mbank", emptySet()))
    }

    @Test
    fun isWhitelisted_isCaseSensitiveExactMatch() {
        val enabled = setOf("pl.mbank")

        assertFalse(BankNotificationFilter.isWhitelisted("PL.MBANK", enabled))
    }
}
