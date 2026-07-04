package org.jarvis.android.sync

import org.bouncycastle.crypto.signers.Ed25519Signer
import org.jarvis.android.data.crypto.SyncCryptoKt
import org.jarvis.android.data.remote.SyncClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure functions extracted from [Pairing] — no network I/O and no
 * Android [android.content.Context], so these run as plain `testDebugUnitTest`.
 */
class PairingProtocolTest {

    @Test
    fun buildSignedPairingRequest_carriesInputFieldsThrough() {
        val identityKp = SyncCryptoKt.generateIdentityKeyPair()
        val kexKp = SyncCryptoKt.generateKexKeyPair()
        val nonce = SyncCryptoKt.b64(SyncCryptoKt.randomNonce())

        val request = Pairing.buildSignedPairingRequest("pixel-9", identityKp, kexKp, nonce)

        assertEquals("pixel-9", request.deviceLabel)
        assertEquals(nonce, request.pairingNonceB64)
        assertEquals(SyncCryptoKt.b64(SyncCryptoKt.encodeX25519Pub(kexKp.pub)), request.kexPubB64)
        assertEquals(SyncCryptoKt.b64(SyncCryptoKt.encodeEd25519Pub(identityKp.pub)), request.identityPubB64)
    }

    @Test
    fun buildSignedPairingRequest_signatureVerifiesOverNonceAndKexPubBytes() {
        val identityKp = SyncCryptoKt.generateIdentityKeyPair()
        val kexKp = SyncCryptoKt.generateKexKeyPair()
        val nonce = SyncCryptoKt.b64(SyncCryptoKt.randomNonce())

        val request = Pairing.buildSignedPairingRequest("pixel-9", identityKp, kexKp, nonce)

        // Server (PairingService) verifies over pairingNonceB64 || kexPubB64 as UTF-8 string bytes.
        val expectedMessage = (nonce + request.kexPubB64).toByteArray(Charsets.UTF_8)
        val verifier = Ed25519Signer()
        verifier.init(false, identityKp.pub)
        verifier.update(expectedMessage, 0, expectedMessage.size)
        assertTrue(verifier.verifySignature(SyncCryptoKt.unb64(request.identitySigB64)))
    }

    @Test
    fun buildSignedPairingRequest_signatureFailsVerificationForTamperedNonce() {
        val identityKp = SyncCryptoKt.generateIdentityKeyPair()
        val kexKp = SyncCryptoKt.generateKexKeyPair()
        val nonce = SyncCryptoKt.b64(SyncCryptoKt.randomNonce())

        val request = Pairing.buildSignedPairingRequest("pixel-9", identityKp, kexKp, nonce)

        val tamperedMessage = ("not-" + nonce + request.kexPubB64).toByteArray(Charsets.UTF_8)
        val verifier = Ed25519Signer()
        verifier.init(false, identityKp.pub)
        verifier.update(tamperedMessage, 0, tamperedMessage.size)
        assertFalse(verifier.verifySignature(SyncCryptoKt.unb64(request.identitySigB64)))
    }

    @Test
    fun deriveSessionKeyFromResponse_matchesIndependentServerSideAgreement() {
        val deviceKexKp = SyncCryptoKt.generateKexKeyPair()
        val deviceKexPub = SyncCryptoKt.encodeX25519Pub(deviceKexKp.pub)
        val serverKexKp = SyncCryptoKt.generateKexKeyPair()
        val serverKexPub = SyncCryptoKt.encodeX25519Pub(serverKexKp.pub)

        val response = SyncClient.PairingResponse(
            serverKexPubB64 = SyncCryptoKt.b64(serverKexPub),
            routingId = "routing-1",
            senderDeviceId = "device-1",
            pairedAt = "2026-07-04T00:00:00Z"
        )

        val deviceSideKey = Pairing.deriveSessionKeyFromResponse(deviceKexKp, deviceKexPub, response)

        // Simulate the server side independently: it agrees using its own priv key and
        // our raw device public key, then derives with the same salt ordering.
        val serverShared = SyncCryptoKt.x25519Agree(serverKexKp.priv, SyncCryptoKt.decodeX25519Pub(deviceKexPub))
        val serverSideKey = SyncCryptoKt.deriveSessionKey(serverShared, deviceKexPub, serverKexPub)

        assertEquals(SyncCryptoKt.b64(serverSideKey), SyncCryptoKt.b64(deviceSideKey))
    }

    @Test
    fun deriveSessionKeyFromResponse_producesThirtyTwoByteKey() {
        val deviceKexKp = SyncCryptoKt.generateKexKeyPair()
        val deviceKexPub = SyncCryptoKt.encodeX25519Pub(deviceKexKp.pub)
        val serverKexKp = SyncCryptoKt.generateKexKeyPair()
        val response = SyncClient.PairingResponse(
            serverKexPubB64 = SyncCryptoKt.b64(SyncCryptoKt.encodeX25519Pub(serverKexKp.pub)),
            routingId = "routing-1",
            senderDeviceId = "device-1",
            pairedAt = "2026-07-04T00:00:00Z"
        )

        val key = Pairing.deriveSessionKeyFromResponse(deviceKexKp, deviceKexPub, response)

        assertEquals(32, key.size)
    }
}
