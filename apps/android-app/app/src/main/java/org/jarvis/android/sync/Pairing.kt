package org.jarvis.android.sync

import android.content.Context
import org.jarvis.android.data.crypto.SyncCryptoKt
import org.jarvis.android.data.remote.SyncClient

/**
 * Drives the device-pairing handshake against sync-service and persists the
 * resulting session so {@link SyncWorker} can start uploading health/finance blobs.
 *
 * Protocol (must match server PairingService):
 *   1. POST /pairing/init      -> { pairingNonce, serverKexPub }
 *   2. generate Ed25519 identity + X25519 kex keypairs
 *   3. sign Ed25519 over (nonce || deviceKexPub)
 *   4. POST /pairing/complete   -> { serverKexPub, routingId, senderDeviceId,
 *                                    serverIdentityPubB64, serverIdentitySigB64 }
 *   5. verify the server's Ed25519 signature over (nonce || serverKexPub) using
 *      serverIdentityPubB64 — and, on re-pair to a previously paired [baseUrl],
 *      that serverIdentityPubB64 matches the pinned value from the first pairing
 *   6. sessionKey = deriveSessionKey(x25519(deviceKexPriv, serverKexPub), deviceKexPub, serverKexPub)
 *
 * <p>Step 5 authenticates the *server* to the device (the device already authenticates
 * itself to the server via step 3's signature). Without it, an on-LAN attacker could
 * intercept `POST /pairing/init` / `/pairing/complete` (the app previously allowed
 * cleartext HTTP) and hand the device its own X25519 keypair, completing a normal-looking
 * handshake while impersonating the real server — a classic MITM. See [ServerAuthResult].</p>
 */
object Pairing {

    /** Thrown by [pair] when the server's authenticity cannot be verified (see [ServerAuthResult.Rejected]). */
    class ServerAuthenticationException(reason: String) :
        RuntimeException("Pairing rejected: server authentication failed ($reason)")

    /** Result of verifying the server's proof of identity during `/pairing/complete`. */
    sealed interface ServerAuthResult {
        data class Verified(val serverIdentityPubB64: String) : ServerAuthResult
        data class Rejected(val reason: String) : ServerAuthResult
    }

    /**
     * Verifies that the party who answered `/pairing/complete` actually holds the
     * private key for [serverIdentityPubB64] by checking its signature over
     * `pairingNonceB64 || serverKexPubB64` (mirroring the device->server signature
     * format in [buildSignedPairingRequest]). Pure function — no network I/O, no
     * [Context] — so it can be unit tested directly.
     *
     * Fails closed: a missing, malformed, or invalid signature is always [ServerAuthResult.Rejected] —
     * never treated as "unverified but OK". When [pinnedServerIdentityPubB64] is non-null
     * (i.e. this device previously paired with this exact `baseUrl`), the server identity
     * must also match the pinned value, so an attacker who compromises the LAN *after*
     * a legitimate first pairing still can't silently swap in a different "server".
     */
    fun verifyServerAuthenticity(
        pairingNonceB64: String,
        serverKexPubB64: String,
        serverIdentityPubB64: String?,
        serverIdentitySigB64: String?,
        pinnedServerIdentityPubB64: String? = null
    ): ServerAuthResult {
        if (serverIdentityPubB64.isNullOrBlank() || serverIdentitySigB64.isNullOrBlank()) {
            return ServerAuthResult.Rejected("missing_server_authentication")
        }
        val verified = try {
            val identityPub = SyncCryptoKt.decodeEd25519Pub(SyncCryptoKt.unb64(serverIdentityPubB64))
            val sig = SyncCryptoKt.unb64(serverIdentitySigB64)
            val message = (pairingNonceB64 + serverKexPubB64).toByteArray(Charsets.UTF_8)
            SyncCryptoKt.verifyEd25519(identityPub, message, sig)
        } catch (e: IllegalArgumentException) {
            false
        }
        if (!verified) {
            return ServerAuthResult.Rejected("invalid_server_signature")
        }
        if (pinnedServerIdentityPubB64 != null && pinnedServerIdentityPubB64 != serverIdentityPubB64) {
            return ServerAuthResult.Rejected("server_identity_mismatch")
        }
        return ServerAuthResult.Verified(serverIdentityPubB64)
    }

    /**
     * Builds the signed `/pairing/complete` request body from freshly generated keys and
     * the server's pairing-init nonce. Pure function (no network I/O, no [Context]) —
     * extracted from [pair] so the signing logic can be unit tested directly.
     */
    fun buildSignedPairingRequest(
        deviceLabel: String,
        identityKp: SyncCryptoKt.IdentityKeyPair,
        kexKp: SyncCryptoKt.KexKeyPair,
        pairingNonceB64: String
    ): SyncClient.PairingRequest {
        val deviceKexPub = SyncCryptoKt.encodeX25519Pub(kexKp.pub)
        val identityPub = SyncCryptoKt.encodeEd25519Pub(identityKp.pub)
        val kexPubB64 = SyncCryptoKt.b64(deviceKexPub)

        // Server (PairingService) verifies the Ed25519 signature over the base64url STRING
        // bytes: pairingNonceB64.getBytes(UTF_8) || kexPubB64.getBytes(UTF_8) — NOT the raw
        // decoded bytes. Sign exactly what goes on the wire.
        val message = (pairingNonceB64 + kexPubB64).toByteArray(Charsets.UTF_8)
        val sig = SyncCryptoKt.signEd25519(identityKp.priv, message)

        return SyncClient.PairingRequest(
            deviceLabel = deviceLabel,
            identityPubB64 = SyncCryptoKt.b64(identityPub),
            kexPubB64 = kexPubB64,
            pairingNonceB64 = pairingNonceB64,
            identitySigB64 = SyncCryptoKt.b64(sig)
        )
    }

    /**
     * Derives the session key from our kex private key and the server's `/pairing/complete`
     * response. Pure function (no network I/O, no [Context]) — extracted from [pair] so the
     * key-agreement logic can be unit tested directly.
     */
    fun deriveSessionKeyFromResponse(
        kexKp: SyncCryptoKt.KexKeyPair,
        deviceKexPub: ByteArray,
        resp: SyncClient.PairingResponse
    ): ByteArray {
        val serverKexPubRaw = SyncCryptoKt.unb64(resp.serverKexPubB64)
        val serverKexPubKey = SyncCryptoKt.decodeX25519Pub(serverKexPubRaw)
        val shared = SyncCryptoKt.x25519Agree(kexKp.priv, serverKexPubKey)
        return SyncCryptoKt.deriveSessionKey(shared, deviceKexPub, serverKexPubRaw)
    }

    fun pair(context: Context, rawBaseUrl: String, deviceLabel: String): SyncClient.PairingResponse {
        val baseUrl = rawBaseUrl.trim().trimEnd('/')
        val client = SyncClient(baseUrl)
        val pairingState = PairingState(context)

        val init = client.pairingInit()

        val identityKp = SyncCryptoKt.generateIdentityKeyPair()
        val kexKp = SyncCryptoKt.generateKexKeyPair()
        val deviceKexPub = SyncCryptoKt.encodeX25519Pub(kexKp.pub)

        val request = buildSignedPairingRequest(deviceLabel, identityKp, kexKp, init.pairingNonceB64)
        val resp = client.pairingComplete(request)

        // Authenticate the SERVER before trusting anything else in the response — see the
        // class doc and [verifyServerAuthenticity]. This must run before deriving/persisting
        // the session key so a MITM's forged response never results in a "paired" session.
        val authResult = verifyServerAuthenticity(
            pairingNonceB64 = init.pairingNonceB64,
            serverKexPubB64 = resp.serverKexPubB64,
            serverIdentityPubB64 = resp.serverIdentityPubB64,
            serverIdentitySigB64 = resp.serverIdentitySigB64,
            pinnedServerIdentityPubB64 = pairingState.pinnedServerIdentityFor(baseUrl)
        )
        val verifiedServerIdentity = when (authResult) {
            is ServerAuthResult.Rejected -> throw ServerAuthenticationException(authResult.reason)
            is ServerAuthResult.Verified -> authResult.serverIdentityPubB64
        }

        val sessionKey = deriveSessionKeyFromResponse(kexKp, deviceKexPub, resp)

        pairingState.save(
            routingId = resp.routingId,
            senderDeviceId = resp.senderDeviceId,
            sessionKeyB64 = SyncCryptoKt.b64(sessionKey),
            baseUrl = baseUrl,
            serverIdentityPubB64 = verifiedServerIdentity
        )
        return resp
    }
}
