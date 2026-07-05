package org.jarvis.android.data.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 12 — Kotlin port of the JVM-side `SyncCrypto`. Wire format and key
 * derivation MUST stay identical to the server. Whenever the JVM version
 * changes, mirror the change here.
 *
 * <p>Ed25519 and X25519 use the **Bouncy Castle lightweight API** directly
 * (no JCA provider registration). This is mandatory for real Android devices:
 * many OEM JCA providers do NOT expose {@code KeyPairGenerator.getInstance("Ed25519")}
 * (or X25519), which previously failed pairing with
 * "Ed25519 KeyPairGenerator not available". BC's lightweight classes produce the
 * exact same raw 32-byte public keys (RFC 7748 / RFC 8032) and raw 64-byte
 * Ed25519 signatures the server expects, so the on-the-wire protocol is unchanged.
 *
 * <p>ChaCha20-Poly1305, HMAC-SHA256 and SHA-256 still use the JCA (universally
 * available on Android API 31+).
 */
object SyncCryptoKt {
    const val X25519_KEY_LEN = 32
    const val ED25519_KEY_LEN = 32
    const val CHACHA_NONCE_LEN = 12

    private const val INFO = "jarvis-sync-v1-d2s"

    /** Ed25519 signing identity keypair (Bouncy Castle lightweight types). */
    class IdentityKeyPair(
        val priv: Ed25519PrivateKeyParameters,
        val pub: Ed25519PublicKeyParameters
    )

    /** X25519 key-exchange keypair (Bouncy Castle lightweight types). */
    class KexKeyPair(
        val priv: X25519PrivateKeyParameters,
        val pub: X25519PublicKeyParameters
    )

    fun generateKexKeyPair(): KexKeyPair {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(SecureRandom()))
        val kp = gen.generateKeyPair()
        return KexKeyPair(
            kp.private as X25519PrivateKeyParameters,
            kp.public as X25519PublicKeyParameters
        )
    }

    fun generateIdentityKeyPair(): IdentityKeyPair {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = gen.generateKeyPair()
        return IdentityKeyPair(
            kp.private as Ed25519PrivateKeyParameters,
            kp.public as Ed25519PublicKeyParameters
        )
    }

    /** Raw 32-byte little-endian X25519 public key (RFC 7748) — the wire format. */
    fun encodeX25519Pub(pub: X25519PublicKeyParameters): ByteArray = pub.encoded

    /** Raw 32-byte compressed Ed25519 public key (RFC 8032) — the wire format. */
    fun encodeEd25519Pub(pub: Ed25519PublicKeyParameters): ByteArray = pub.encoded

    fun decodeX25519Pub(raw: ByteArray): X25519PublicKeyParameters {
        require(raw.size == X25519_KEY_LEN) { "X25519 pubkey must be $X25519_KEY_LEN bytes, got ${raw.size}" }
        return X25519PublicKeyParameters(raw, 0)
    }

    fun x25519Agree(ourPriv: X25519PrivateKeyParameters, theirPub: X25519PublicKeyParameters): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(ourPriv)
        val secret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(theirPub, secret, 0)
        return secret
    }

    fun deriveSessionKey(shared: ByteArray, devicePub: ByteArray, serverPub: ByteArray): ByteArray {
        val salt = ByteBuffer.allocate(devicePub.size + serverPub.size)
            .put(devicePub).put(serverPub).array()
        val prk = hmacSha256(salt, shared)
        return hkdfExpand(prk, INFO.toByteArray(StandardCharsets.UTF_8), 32)
    }

    fun signEd25519(priv: Ed25519PrivateKeyParameters, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, priv)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        require(nonce.size == CHACHA_NONCE_LEN)
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        c.updateAAD(aad)
        return c.doFinal(plaintext)
    }

    fun randomNonce(): ByteArray {
        val n = ByteArray(CHACHA_NONCE_LEN)
        SecureRandom().nextBytes(n)
        return n
    }

    fun deviceAlias(identityPub: ByteArray): String {
        val h = MessageDigest.getInstance("SHA-256").digest(identityPub)
        val hex = h.copyOf(8).joinToString("") { "%02x".format(it) }
        return "dev-$hex"
    }

    fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        val out = ByteArray(length)
        var t = ByteArray(0)
        var written = 0
        var counter: Byte = 1
        while (written < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter)
            t = mac.doFinal()
            val take = minOf(t.size, length - written)
            System.arraycopy(t, 0, out, written, take)
            written += take
            counter++
        }
        return out
    }
}
