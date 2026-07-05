package org.jarvis.android.data.crypto

import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-JVM unit tests for [SyncCryptoKt]. It only touches
 * org.bouncycastle.* / javax.crypto / java.security, so this runs as a plain
 * `testDebugUnitTest` — no Robolectric / Android framework needed.
 */
class SyncCryptoKtTest {

    @Test
    fun x25519AgreementIsSymmetric() {
        val alice = SyncCryptoKt.generateKexKeyPair()
        val bob = SyncCryptoKt.generateKexKeyPair()

        val aliceShared = SyncCryptoKt.x25519Agree(alice.priv, bob.pub)
        val bobShared = SyncCryptoKt.x25519Agree(bob.priv, alice.pub)

        assertArrayEquals(aliceShared, bobShared)
    }

    @Test
    fun ed25519SignAndVerifyRoundtrip() {
        val identity = SyncCryptoKt.generateIdentityKeyPair()
        val message = "pair-me-please".toByteArray(StandardCharsets.UTF_8)

        val signature = SyncCryptoKt.signEd25519(identity.priv, message)

        val verifier = Ed25519Signer()
        verifier.init(false, identity.pub)
        verifier.update(message, 0, message.size)
        assertTrue(verifier.verifySignature(signature))
    }

    @Test
    fun ed25519VerifyFailsForTamperedMessage() {
        val identity = SyncCryptoKt.generateIdentityKeyPair()
        val message = "pair-me-please".toByteArray(StandardCharsets.UTF_8)
        val tampered = "pair-me-PLEASE!".toByteArray(StandardCharsets.UTF_8)

        val signature = SyncCryptoKt.signEd25519(identity.priv, message)

        val verifier = Ed25519Signer()
        verifier.init(false, identity.pub)
        verifier.update(tampered, 0, tampered.size)
        assertFalse(verifier.verifySignature(signature))
    }

    @Test
    fun chacha20Poly1305SealAndOpenRoundtrip() {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val nonce = SyncCryptoKt.randomNonce()
        val aad = "envelope-v1".toByteArray(StandardCharsets.UTF_8)
        val plaintext = "balance: 42.50 EUR".toByteArray(StandardCharsets.UTF_8)

        val ciphertext = SyncCryptoKt.seal(key, nonce, aad, plaintext)
        val opened = open(key, nonce, aad, ciphertext)

        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun chacha20Poly1305OpenFailsWhenCiphertextIsTampered() {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val nonce = SyncCryptoKt.randomNonce()
        val aad = "envelope-v1".toByteArray(StandardCharsets.UTF_8)
        val plaintext = "balance: 42.50 EUR".toByteArray(StandardCharsets.UTF_8)

        val ciphertext = SyncCryptoKt.seal(key, nonce, aad, plaintext)
        val tampered = ciphertext.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()

        assertThrows(AEADBadTagException::class.java) {
            open(key, nonce, aad, tampered)
        }
    }

    /**
     * Manual ChaCha20-Poly1305 decrypt mirroring what the receiving side does.
     * [SyncCryptoKt] only exposes `seal` (the device is always the sender), so the
     * "open" half is reimplemented here purely to exercise the roundtrip/tamper cases.
     */
    private fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        c.updateAAD(aad)
        return c.doFinal(ciphertext)
    }
}
