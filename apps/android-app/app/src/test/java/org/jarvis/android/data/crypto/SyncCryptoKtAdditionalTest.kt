package org.jarvis.android.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Covers the [SyncCryptoKt] members not already exercised by [SyncCryptoKtTest]:
 * device aliasing, the base64url helpers, and the input-validation (`require`)
 * branches on `decodeX25519Pub` / `seal`. Pure JVM — only touches
 * org.bouncycastle.* / javax.crypto / java.security, same as the sibling test.
 */
class SyncCryptoKtAdditionalTest {

    @Test
    fun deviceAlias_isDeterministicForSameInput() {
        val pub = ByteArray(32).also { SecureRandom().nextBytes(it) }

        assertEquals(SyncCryptoKt.deviceAlias(pub), SyncCryptoKt.deviceAlias(pub))
    }

    @Test
    fun deviceAlias_differsForDifferentInput() {
        val pubA = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pubB = ByteArray(32).also { SecureRandom().nextBytes(it) }

        assertNotEquals(SyncCryptoKt.deviceAlias(pubA), SyncCryptoKt.deviceAlias(pubB))
    }

    @Test
    fun deviceAlias_hasDevPrefixAndSixteenHexChars() {
        val pub = ByteArray(32).also { SecureRandom().nextBytes(it) }

        val alias = SyncCryptoKt.deviceAlias(pub)

        assertTrue(alias.startsWith("dev-"))
        assertEquals(20, alias.length) // "dev-" + 16 hex chars (first 8 digest bytes)
        assertTrue(alias.substring(4).matches(Regex("[0-9a-f]{16}")))
    }

    @Test
    fun b64_unb64_roundtripsArbitraryBytes() {
        val original = ByteArray(40).also { SecureRandom().nextBytes(it) }

        val decoded = SyncCryptoKt.unb64(SyncCryptoKt.b64(original))

        assertArrayEquals(original, decoded)
    }

    @Test
    fun b64_producesUrlSafeUnpaddedOutput() {
        // A single byte would normally need "==" padding in standard base64; b64() strips it
        // and must avoid the '+'/'/' characters that are unsafe in URLs.
        val encoded = SyncCryptoKt.b64(byteArrayOf(1))

        assertFalse(encoded.contains("="))
        assertFalse(encoded.contains("+"))
        assertFalse(encoded.contains("/"))
    }

    @Test
    fun decodeX25519Pub_throwsForWrongLength() {
        val tooShort = ByteArray(10)

        val error = assertThrows(IllegalArgumentException::class.java) {
            SyncCryptoKt.decodeX25519Pub(tooShort)
        }
        assertTrue(error.message!!.contains("32"))
    }

    @Test
    fun decodeX25519Pub_succeedsForCorrectLength() {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }

        val key = SyncCryptoKt.decodeX25519Pub(raw)

        assertArrayEquals(raw, SyncCryptoKt.encodeX25519Pub(key))
    }

    @Test
    fun seal_throwsForWrongNonceLength() {
        val key = ByteArray(32)
        val badNonce = ByteArray(4)

        assertThrows(IllegalArgumentException::class.java) {
            SyncCryptoKt.seal(key, badNonce, ByteArray(0), byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun randomNonce_hasChachaNonceLength() {
        val nonce = SyncCryptoKt.randomNonce()

        assertEquals(SyncCryptoKt.CHACHA_NONCE_LEN, nonce.size)
    }

    @Test
    fun randomNonce_variesBetweenCalls() {
        val first = SyncCryptoKt.randomNonce()
        val second = SyncCryptoKt.randomNonce()

        assertNotEquals(first.toList(), second.toList())
    }
}
