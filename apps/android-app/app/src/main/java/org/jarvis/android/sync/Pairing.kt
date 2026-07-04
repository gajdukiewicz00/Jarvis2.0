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
 *   4. POST /pairing/complete   -> { serverKexPub, routingId, senderDeviceId }
 *   5. sessionKey = deriveSessionKey(x25519(deviceKexPriv, serverKexPub), deviceKexPub, serverKexPub)
 */
object Pairing {

    fun pair(context: Context, rawBaseUrl: String, deviceLabel: String): SyncClient.PairingResponse {
        val baseUrl = rawBaseUrl.trim().trimEnd('/')
        val client = SyncClient(baseUrl)

        val init = client.pairingInit()

        val identityKp = SyncCryptoKt.generateIdentityKeyPair()
        val kexKp = SyncCryptoKt.generateKexKeyPair()
        val deviceKexPub = SyncCryptoKt.encodeX25519Pub(kexKp.pub)
        val identityPub = SyncCryptoKt.encodeEd25519Pub(identityKp.pub)
        val kexPubB64 = SyncCryptoKt.b64(deviceKexPub)

        // Server (PairingService) verifies the Ed25519 signature over the base64url STRING
        // bytes: pairingNonceB64.getBytes(UTF_8) || kexPubB64.getBytes(UTF_8) — NOT the raw
        // decoded bytes. Sign exactly what goes on the wire.
        val message = (init.pairingNonceB64 + kexPubB64).toByteArray(Charsets.UTF_8)
        val sig = SyncCryptoKt.signEd25519(identityKp.priv, message)

        val resp = client.pairingComplete(
            SyncClient.PairingRequest(
                deviceLabel = deviceLabel,
                identityPubB64 = SyncCryptoKt.b64(identityPub),
                kexPubB64 = kexPubB64,
                pairingNonceB64 = init.pairingNonceB64,
                identitySigB64 = SyncCryptoKt.b64(sig)
            )
        )

        val serverKexPubRaw = SyncCryptoKt.unb64(resp.serverKexPubB64)
        val serverKexPubKey = SyncCryptoKt.decodeX25519Pub(serverKexPubRaw)
        val shared = SyncCryptoKt.x25519Agree(kexKp.priv, serverKexPubKey)
        val sessionKey = SyncCryptoKt.deriveSessionKey(shared, deviceKexPub, serverKexPubRaw)

        PairingState(context).save(
            routingId = resp.routingId,
            senderDeviceId = resp.senderDeviceId,
            sessionKeyB64 = SyncCryptoKt.b64(sessionKey),
            baseUrl = baseUrl
        )
        return resp
    }
}
