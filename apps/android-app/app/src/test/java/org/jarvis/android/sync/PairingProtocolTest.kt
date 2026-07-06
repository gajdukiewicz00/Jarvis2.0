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

    // --- verifyServerAuthenticity (finding #11: server authentication / anti-MITM) ---

    private fun signedServerAuth(
        nonceB64: String,
        serverKexPubB64: String,
        serverIdentityKp: SyncCryptoKt.IdentityKeyPair = SyncCryptoKt.generateIdentityKeyPair()
    ): Triple<String, String, SyncCryptoKt.IdentityKeyPair> {
        val message = (nonceB64 + serverKexPubB64).toByteArray(Charsets.UTF_8)
        val sig = SyncCryptoKt.signEd25519(serverIdentityKp.priv, message)
        val identityPubB64 = SyncCryptoKt.b64(SyncCryptoKt.encodeEd25519Pub(serverIdentityKp.pub))
        return Triple(identityPubB64, SyncCryptoKt.b64(sig), serverIdentityKp)
    }

    @Test
    fun verifyServerAuthenticity_acceptsValidServerSignature() {
        val nonce = SyncCryptoKt.b64(SyncCryptoKt.randomNonce())
        val serverKexPub = SyncCryptoKt.b64(SyncCryptoKt.encodeX25519Pub(SyncCryptoKt.generateKexKeyPair().pub))
        val (identityPubB64, sigB64, _) = signedServerAuth(nonce, serverKexPub)

        val result = Pairing.verifyServerAuthenticity(nonce, serverKexPub, identityPubB64, sigB64)

        assertTrue(result is Pairing.ServerAuthResult.Verified)
    }

    @Test
    fun verifyServerAuthenticity_rejectsMissingServerSignatureAndIdentity() {
        // This is the CVE-shaped regression case for finding #11: a MITM that simply
        // relays/forges a `/pairing/complete` response with no server proof at all
        // (the original vulnerable behavior — nothing was ever checked) must be rejected.
        val nonce = SyncCryptoKt.b64(SyncCryptoKt.randomNonce())
        val serverKexPub = SyncCryptoKt.b64(SyncCryptoKt.encodeX25519Pub(SyncCryptoKt.generateKexKeyPair().pub))

        val result = Pairing.verifyServerAuthenticity(nonce, serverKexPub, null, null)

        assertTrue(result is Pairing.ServerAuthResult.Rejected)
        assertEquals("missing_server_authentication", (result as Pairing.ServerAuthResult.Rejected).reason)
    }

    @Test
    fun verifyServerAuthenticity_rejectsSignaturePresentButIdentityMissing() {
        val nonce = SyncCryptoKt.b64(SyncCryptoKt.randomNonce())
        val serverKexPub = SyncCryptoKt.b64(SyncCryptoKt.encodeX25519Pub(SyncCryptoKt.generateKexKeyPair().pub))
        val (_, sigB64, _) = signedServerAuth(nonce, serverKexPub)

        val result = Pairing.verifyServerAuthenticity(nonce, serverKexPub, null, sigB64)

        assertTrue(result is Pairing.ServerAuthResult.Rejected)
    }

    @Test
    fun verifyServerAuthenticity_rejectsInvalidServerSignature() {
        // An attacker who forges its own identity keypair but signs the WRONG message
        // (e.g. a different serverKexPub than the one actually returned, or a stale
        // nonce) must be rejected — the signature has to verify over the exact bytes.
        val nonce = SyncCryptoKt.b64(SyncCryptoKt.randomNonce())
        val serverKexPub = SyncCryptoKt.b64(SyncCryptoKt.encodeX25519Pub(SyncCryptoKt.generateKexKeyPair().pub))
        val tamperedKexPub = SyncCryptoKt.b64(SyncCryptoKt.encodeX25519Pub(SyncCryptoKt.generateKexKeyPair().pub))
        val (identityPubB64, sigB64, _) = signedServerAuth(nonce, tamperedKexPub)

        // Signature was computed over `tamperedKexPub`, but we present `serverKexPub`.
        val result = Pairing.verifyServerAuthenticity(nonce, serverKexPub, identityPubB64, sigB64)

        assertTrue(result is Pairing.ServerAuthResult.Rejected)
        assertEquals("invalid_server_signature", (result as Pairing.ServerAuthResult.Rejected).reason)
    }

    @Test
    fun verifyServerAuthenticity_rejectsMalformedBase64Fields() {
        val nonce = SyncCryptoKt.b64(SyncCryptoKt.randomNonce())
        val serverKexPub = SyncCryptoKt.b64(SyncCryptoKt.encodeX25519Pub(SyncCryptoKt.generateKexKeyPair().pub))

        val result = Pairing.verifyServerAuthenticity(nonce, serverKexPub, "not-valid-base64!!", "also-not-valid!!")

        assertTrue(result is Pairing.ServerAuthResult.Rejected)
    }

    @Test
    fun verifyServerAuthenticity_rejectsWhenPinnedIdentityDoesNotMatch() {
        // A validly self-signed response from a DIFFERENT server identity than the one
        // pinned on first pairing must still be rejected — this catches an attacker who
        // compromises the LAN after a legitimate first pairing and stands up their own
        // (self-consistently signed) fake server.
        val nonce = SyncCryptoKt.b64(SyncCryptoKt.randomNonce())
        val serverKexPub = SyncCryptoKt.b64(SyncCryptoKt.encodeX25519Pub(SyncCryptoKt.generateKexKeyPair().pub))
        val (attackerIdentityPubB64, sigB64, _) = signedServerAuth(nonce, serverKexPub)
        val pinnedIdentityPubB64 = SyncCryptoKt.b64(
            SyncCryptoKt.encodeEd25519Pub(SyncCryptoKt.generateIdentityKeyPair().pub)
        )

        val result = Pairing.verifyServerAuthenticity(
            nonce, serverKexPub, attackerIdentityPubB64, sigB64, pinnedIdentityPubB64
        )

        assertTrue(result is Pairing.ServerAuthResult.Rejected)
        assertEquals("server_identity_mismatch", (result as Pairing.ServerAuthResult.Rejected).reason)
    }

    @Test
    fun verifyServerAuthenticity_acceptsWhenIdentityMatchesPinnedValue() {
        val nonce = SyncCryptoKt.b64(SyncCryptoKt.randomNonce())
        val serverKexPub = SyncCryptoKt.b64(SyncCryptoKt.encodeX25519Pub(SyncCryptoKt.generateKexKeyPair().pub))
        val (identityPubB64, sigB64, _) = signedServerAuth(nonce, serverKexPub)

        val result = Pairing.verifyServerAuthenticity(
            nonce, serverKexPub, identityPubB64, sigB64, pinnedServerIdentityPubB64 = identityPubB64
        )

        assertTrue(result is Pairing.ServerAuthResult.Verified)
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
