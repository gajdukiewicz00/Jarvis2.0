package org.jarvis.sync.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SyncCryptoTest {

    private final SyncCrypto crypto = new SyncCrypto();

    @Test
    void x25519AgreementSymmetric() {
        KeyPair device = crypto.generateKexKeyPair();
        KeyPair server = crypto.generateKexKeyPair();
        byte[] ds = crypto.x25519Agree(device.getPrivate(), server.getPublic());
        byte[] sd = crypto.x25519Agree(server.getPrivate(), device.getPublic());
        assertThat(ds).isEqualTo(sd);
    }

    @Test
    void x25519PubRoundTripsThroughRawEncoding() {
        KeyPair device = crypto.generateKexKeyPair();
        byte[] raw = crypto.encodeX25519Pub(device.getPublic());
        assertThat(raw).hasSize(SyncCrypto.X25519_KEY_LEN);
        var decoded = crypto.decodeX25519Pub(raw);
        // Same shared secret if we agree against the decoded pubkey.
        KeyPair server = crypto.generateKexKeyPair();
        byte[] viaOriginal = crypto.x25519Agree(server.getPrivate(), device.getPublic());
        byte[] viaDecoded  = crypto.x25519Agree(server.getPrivate(), decoded);
        assertThat(viaDecoded).isEqualTo(viaOriginal);
    }

    @Test
    void sessionKeyDerivedDeterministicallyFromSharedSecretAndPubkeys() {
        KeyPair device = crypto.generateKexKeyPair();
        KeyPair server = crypto.generateKexKeyPair();
        byte[] devicePub = crypto.encodeX25519Pub(device.getPublic());
        byte[] serverPub = crypto.encodeX25519Pub(server.getPublic());
        byte[] secretA = crypto.x25519Agree(device.getPrivate(), server.getPublic());
        byte[] secretB = crypto.x25519Agree(server.getPrivate(), device.getPublic());
        SessionKeys k1 = crypto.deriveSessionKey(secretA.clone(), devicePub, serverPub);
        SessionKeys k2 = crypto.deriveSessionKey(secretB.clone(), devicePub, serverPub);
        assertThat(k1.key()).isEqualTo(k2.key());
    }

    @Test
    void sealOpenRoundTrip() throws Exception {
        SessionKeys key = newSessionKey();
        byte[] nonce = crypto.randomNonce();
        byte[] aad = "routing-id|sender-id|1".getBytes(StandardCharsets.UTF_8);
        byte[] plain = "hello jarvis".getBytes(StandardCharsets.UTF_8);
        byte[] ct = crypto.seal(key, nonce, aad, plain);
        assertThat(ct).hasSize(plain.length + SyncCrypto.CHACHA_TAG_LEN);
        byte[] back = crypto.open(key, nonce, aad, ct);
        assertThat(back).isEqualTo(plain);
    }

    @Test
    void aadMismatchTripsTag() throws Exception {
        SessionKeys key = newSessionKey();
        byte[] nonce = crypto.randomNonce();
        byte[] ct = crypto.seal(key, nonce,
                "routing-A".getBytes(StandardCharsets.UTF_8),
                "secret".getBytes(StandardCharsets.UTF_8));
        assertThrows(SyncCrypto.AeadAuthException.class,
                () -> crypto.open(key, nonce,
                        "routing-B".getBytes(StandardCharsets.UTF_8), ct));
    }

    @Test
    void ciphertextTamperingTripsTag() throws Exception {
        SessionKeys key = newSessionKey();
        byte[] nonce = crypto.randomNonce();
        byte[] aad = "aad".getBytes(StandardCharsets.UTF_8);
        byte[] ct = crypto.seal(key, nonce, aad, "secret".getBytes(StandardCharsets.UTF_8));
        ct[0] ^= 0x01;
        assertThrows(SyncCrypto.AeadAuthException.class,
                () -> crypto.open(key, nonce, aad, ct));
    }

    @Test
    void ed25519SignVerifyRoundTrip() {
        KeyPair id = crypto.generateIdentityKeyPair();
        byte[] msg = "pair-this-device".getBytes(StandardCharsets.UTF_8);
        byte[] sig = crypto.signEd25519(id.getPrivate(), msg);
        assertThat(crypto.verifyEd25519(id.getPublic(), msg, sig)).isTrue();
        msg[0] ^= 0x01;
        assertThat(crypto.verifyEd25519(id.getPublic(), msg, sig)).isFalse();
    }

    @Test
    void ed25519PubRoundTripsThroughRawEncoding() {
        KeyPair id = crypto.generateIdentityKeyPair();
        byte[] raw = crypto.encodeEd25519Pub(id.getPublic());
        assertThat(raw).hasSize(SyncCrypto.ED25519_KEY_LEN);
        var decoded = crypto.decodeEd25519Pub(raw);
        byte[] msg = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] sig = crypto.signEd25519(id.getPrivate(), msg);
        assertThat(crypto.verifyEd25519(decoded, msg, sig)).isTrue();
    }

    @Test
    void deviceAliasIsStableAndOpaque() {
        byte[] id = new byte[SyncCrypto.ED25519_KEY_LEN];
        for (int i = 0; i < id.length; i++) id[i] = (byte) i;
        String a = crypto.deviceAlias(id);
        String b = crypto.deviceAlias(id);
        assertThat(a).isEqualTo(b).startsWith("dev-").hasSize("dev-".length() + 16);
    }

    @Test
    void base64HelpersAreUrlSafeUnpadded() {
        byte[] raw = new byte[]{(byte) 0xff, (byte) 0xee, (byte) 0xdd};
        String enc = SyncCrypto.b64(raw);
        assertThat(enc).doesNotContain("=").doesNotContain("/").doesNotContain("+");
        assertThat(SyncCrypto.unb64(enc)).isEqualTo(raw);
    }

    private SessionKeys newSessionKey() {
        byte[] k = new byte[SyncCrypto.CHACHA_KEY_LEN];
        for (int i = 0; i < k.length; i++) k[i] = (byte) (i * 7 + 1);
        return new SessionKeys(k);
    }
}
