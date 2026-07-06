package org.jarvis.android.sync

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Phase 12 — at-rest storage for paired-device material on Android.
 *
 * <p>Backed by {@link EncryptedSharedPreferences} so that on a rooted
 * device the session key is not directly readable from disk. The keys
 * persisted here:</p>
 * <ul>
 *   <li>{@code routingId} — opaque cloud-relay routing key</li>
 *   <li>{@code senderDeviceId} — alias the server assigned us</li>
 *   <li>{@code sessionKeyB64} — derived ChaCha20-Poly1305 key</li>
 *   <li>{@code identityPrivPkcs8B64} — Ed25519 private (for re-pairing)</li>
 *   <li>{@code serverIdentityPubB64} — the server's Ed25519 identity pubkey, pinned
 *       (trust-on-first-use) at pairing time so a later re-pair to the same
 *       {@code baseUrl} can detect a server-identity swap (see {@link Pairing})</li>
 * </ul>
 *
 * <p>Re-pairing produces fresh values; the previous record is
 * overwritten with no migration.</p>
 */
class PairingState(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "jarvis-pairing",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun isPaired(): Boolean = prefs.contains("routingId")

    fun save(
        routingId: String,
        senderDeviceId: String,
        sessionKeyB64: String,
        baseUrl: String,
        serverIdentityPubB64: String
    ) {
        prefs.edit()
            .putString("routingId", routingId)
            .putString("senderDeviceId", senderDeviceId)
            .putString("sessionKeyB64", sessionKeyB64)
            .putString("baseUrl", baseUrl)
            .putString("serverIdentityPubB64", serverIdentityPubB64)
            .apply()
    }

    fun routingId(): String? = prefs.getString("routingId", null)
    fun senderDeviceId(): String? = prefs.getString("senderDeviceId", null)
    fun sessionKeyB64(): String? = prefs.getString("sessionKeyB64", null)
    fun baseUrl(): String? = prefs.getString("baseUrl", null)
    fun serverIdentityPubB64(): String? = prefs.getString("serverIdentityPubB64", null)

    /**
     * The previously pinned server identity to check a *new* pairing against, but only
     * when re-pairing against the same [baseUrl] — a fresh [baseUrl] is a new trust root
     * and has nothing to pin against yet (trust-on-first-use).
     */
    fun pinnedServerIdentityFor(baseUrl: String): String? =
        if (baseUrl() == baseUrl) serverIdentityPubB64() else null

    fun forget() {
        prefs.edit().clear().apply()
    }
}
