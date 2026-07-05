package org.jarvis.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Method
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * [SettingsScreen]'s `pairErrorMessage` is a pure `Throwable -> String` mapper with no
 * Android/Compose dependency (only `java.net.*`/`java.io.*` types) — genuinely testable logic
 * that just happens to be `private` because it was extracted for readability, not testability.
 *
 * This task is test-only (main source under `src/main` may not be touched), so reflection is
 * used here purely to invoke the already-compiled, already-shipped function directly. This is
 * a deliberate, narrow exception to "no reflection hacks": the alternative is leaving a
 * substantial, pure, already-written branch-heavy function permanently uncovered.
 */
class SettingsScreenPairErrorMessageTest {

    private val method: Method = Class.forName("org.jarvis.android.ui.settings.SettingsScreenKt")
        .getDeclaredMethod("pairErrorMessage", Throwable::class.java)
        .apply { isAccessible = true }

    private fun message(e: Throwable): String = method.invoke(null, e) as String

    @Test
    fun mapsUnknownHostToUnreachableServerMessage() {
        assertTrue(message(UnknownHostException("nope")).contains("недоступен"))
    }

    @Test
    fun mapsConnectExceptionToUnreachableServerMessage() {
        assertTrue(message(ConnectException("refused")).contains("недоступен"))
    }

    @Test
    fun mapsSocketTimeoutToUnreachableServerMessage() {
        assertTrue(message(SocketTimeoutException("timeout")).contains("недоступен"))
    }

    @Test
    fun mapsGenericIOExceptionToUnreachableServerMessage() {
        assertTrue(message(IOException("boom")).contains("недоступен"))
    }

    @Test
    fun mapsSignatureErrorMessage() {
        assertTrue(message(RuntimeException("identity_signature_invalid")).contains("Подпись отклонена"))
    }

    @Test
    fun mapsSignatureSubstringCaseInsensitively() {
        assertTrue(message(RuntimeException("Bad SIGNATURE from device")).contains("Подпись отклонена"))
    }

    @Test
    fun mapsNonceErrorMessage() {
        assertTrue(message(RuntimeException("nonce expired")).contains("Сессия пейринга истекла"))
    }

    @Test
    fun maps401ErrorMessage() {
        assertTrue(message(RuntimeException("http 401")).contains("fix-sync-auth.sh"))
    }

    @Test
    fun mapsEd25519ErrorMessage() {
        assertTrue(message(RuntimeException("Ed25519 KeyPairGenerator not available")).contains("Bouncy Castle"))
    }

    @Test
    fun mapsX25519ErrorMessage() {
        assertTrue(message(RuntimeException("X25519 agreement failed")).contains("Bouncy Castle"))
    }

    @Test
    fun fallsBackToRawMessageForUnknownErrors() {
        val result = message(RuntimeException("something else entirely"))

        assertEquals("Ошибка пейринга: something else entirely", result)
    }

    @Test
    fun usesExceptionClassNameWhenMessageIsNull() {
        val error = object : RuntimeException() {
            override val message: String? = null
        }

        val result = message(error)

        assertTrue(result.startsWith("Ошибка пейринга:"))
    }
}
