package org.jarvis.android.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpGuardTest {

    @Test
    fun isOtpOrAuthCode_detectsOtpKeywordInTitle() {
        assertTrue(OtpGuard.isOtpOrAuthCode("Your OTP is 123456", null))
    }

    @Test
    fun isOtpOrAuthCode_detectsOneTimePasswordPhrase() {
        assertTrue(OtpGuard.isOtpOrAuthCode(null, "Your one-time password is 445566"))
    }

    @Test
    fun isOtpOrAuthCode_detectsVerificationCode() {
        assertTrue(OtpGuard.isOtpOrAuthCode("Verification code", "Use 998877 to verify your login"))
    }

    @Test
    fun isOtpOrAuthCode_detectsAuthorizationCode() {
        assertTrue(OtpGuard.isOtpOrAuthCode("Transfer authorization code", "112233"))
    }

    @Test
    fun isOtpOrAuthCode_detects2faKeyword() {
        assertTrue(OtpGuard.isOtpOrAuthCode("2FA required", null))
    }

    @Test
    fun isOtpOrAuthCode_detectsPolishKodAutoryzacyjny() {
        assertTrue(OtpGuard.isOtpOrAuthCode("mBank", "Twój kod autoryzacyjny to 123456"))
    }

    @Test
    fun isOtpOrAuthCode_detectsPolishHasloJednorazowe() {
        assertTrue(OtpGuard.isOtpOrAuthCode(null, "Twoje hasło jednorazowe: 998877"))
    }

    @Test
    fun isOtpOrAuthCode_isCaseInsensitive() {
        assertTrue(OtpGuard.isOtpOrAuthCode("YOUR OTP CODE", null))
    }

    @Test
    fun isOtpOrAuthCode_falseForRegularTransactionNotification() {
        assertFalse(OtpGuard.isOtpOrAuthCode("mBank", "Card payment of 42.50 PLN at Biedronka"))
    }

    @Test
    fun isOtpOrAuthCode_falseForBothNull() {
        assertFalse(OtpGuard.isOtpOrAuthCode(null, null))
    }

    @Test
    fun isOtpOrAuthCode_falseForBlankStrings() {
        assertFalse(OtpGuard.isOtpOrAuthCode("", ""))
    }
}
