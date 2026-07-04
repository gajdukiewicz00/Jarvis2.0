package org.jarvis.sync.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.XECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Phase 12 — central crypto helper for the sync protocol.
 *
 * <p>Pure JDK 21 primitives:</p>
 * <ul>
 *   <li>X25519 ECDH for pairing key agreement</li>
 *   <li>HKDF-SHA256 for session-key derivation</li>
 *   <li>ChaCha20-Poly1305 (AEAD) for blob seal/open</li>
 *   <li>Ed25519 for the device-identity attestation signature</li>
 * </ul>
 *
 * <p>All public-key encodings on the wire are <strong>raw 32-byte
 * little-endian point bytes</strong>, base64url (no padding). This
 * matches what {@code libsodium} / {@code monocypher} produce on the
 * Android side.</p>
 */
public final class SyncCrypto {

    public static final int X25519_KEY_LEN = 32;
    public static final int ED25519_KEY_LEN = 32;
    public static final int CHACHA_KEY_LEN = 32;
    public static final int CHACHA_NONCE_LEN = 12;
    public static final int CHACHA_TAG_LEN = 16;

    private static final String INFO_DEVICE_TO_SERVER = "jarvis-sync-v1-d2s";
    private static final byte[] HKDF_INFO = INFO_DEVICE_TO_SERVER.getBytes(StandardCharsets.UTF_8);

    private final SecureRandom random;

    public SyncCrypto() {
        this(new SecureRandom());
    }

    public SyncCrypto(SecureRandom random) {
        this.random = random;
    }

    // ---------- X25519 ----------

    public KeyPair generateKexKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("X25519");
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("X25519 unavailable", e);
        }
    }

    /** Encode an X25519 public key as raw 32-byte little-endian point bytes. */
    public byte[] encodeX25519Pub(PublicKey pub) {
        if (!(pub instanceof XECPublicKey x)) {
            throw new IllegalArgumentException("not an X25519 public key");
        }
        byte[] out = new byte[X25519_KEY_LEN];
        byte[] u = x.getU().toByteArray();
        // BigInteger.toByteArray is big-endian, may carry sign byte; we want little-endian.
        // Strip leading sign byte if present, pad/truncate to 32 bytes, then reverse.
        int copyLen = Math.min(u.length, X25519_KEY_LEN);
        // Take the trailing copyLen bytes (least-significant) into out little-endian.
        for (int i = 0; i < copyLen; i++) {
            out[i] = u[u.length - 1 - i];
        }
        return out;
    }

    /** Decode an X25519 public key from raw 32-byte little-endian point bytes. */
    public PublicKey decodeX25519Pub(byte[] raw) {
        if (raw == null || raw.length != X25519_KEY_LEN) {
            throw new IllegalArgumentException("X25519 pubkey must be 32 bytes");
        }
        try {
            // BigInteger needs big-endian; reverse a copy.
            byte[] be = new byte[X25519_KEY_LEN];
            for (int i = 0; i < X25519_KEY_LEN; i++) {
                be[i] = raw[X25519_KEY_LEN - 1 - i];
            }
            // RFC 7748 §5: clear the high bit when encoding u-coord on the wire.
            be[0] &= 0x7F;
            java.math.BigInteger u = new java.math.BigInteger(1, be);
            KeyFactory kf = KeyFactory.getInstance("X25519");
            return kf.generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, u));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("X25519 decode failed", e);
        }
    }

    /** Compute the X25519 shared secret. */
    public byte[] x25519Agree(PrivateKey ourPriv, PublicKey theirPub) {
        try {
            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(ourPriv);
            ka.doPhase(theirPub, true);
            return ka.generateSecret();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("X25519 agreement failed", e);
        }
    }

    // ---------- Session-key derivation ----------

    /**
     * Derive a 32-byte ChaCha20-Poly1305 session key from a freshly-agreed
     * X25519 shared secret. The salt mixes both kex pubkeys so a swap of
     * either side derives a different key.
     */
    public SessionKeys deriveSessionKey(byte[] sharedSecret, byte[] devicePub, byte[] serverPub) {
        ByteBuffer salt = ByteBuffer.allocate(devicePub.length + serverPub.length);
        salt.put(devicePub).put(serverPub);
        byte[] key = Hkdf.derive(salt.array(), sharedSecret, HKDF_INFO, CHACHA_KEY_LEN);
        Arrays.fill(sharedSecret, (byte) 0);
        return new SessionKeys(key);
    }

    // ---------- ChaCha20-Poly1305 AEAD ----------

    /**
     * Seal {@code plaintext} under {@code key} with the given 12-byte
     * {@code nonce}; AAD binds the ciphertext to the wire envelope's
     * routing metadata so swapping any of those fields trips the AEAD tag.
     *
     * @return ciphertext with appended 16-byte Poly1305 tag.
     */
    public byte[] seal(SessionKeys key, byte[] nonce, byte[] aad, byte[] plaintext) {
        if (nonce == null || nonce.length != CHACHA_NONCE_LEN) {
            throw new IllegalArgumentException("nonce must be 12 bytes");
        }
        try {
            Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.key(), "ChaCha20"),
                    new IvParameterSpec(nonce));
            if (aad != null) c.updateAAD(aad);
            return c.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AEAD seal failed", e);
        }
    }

    /**
     * Open and authenticate a sealed ciphertext. Throws if the tag does
     * not verify or if AAD differs.
     */
    public byte[] open(SessionKeys key, byte[] nonce, byte[] aad, byte[] ciphertext)
            throws AeadAuthException {
        if (nonce == null || nonce.length != CHACHA_NONCE_LEN) {
            throw new IllegalArgumentException("nonce must be 12 bytes");
        }
        try {
            Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.key(), "ChaCha20"),
                    new IvParameterSpec(nonce));
            if (aad != null) c.updateAAD(aad);
            return c.doFinal(ciphertext);
        } catch (javax.crypto.AEADBadTagException tag) {
            throw new AeadAuthException("AEAD tag mismatch", tag);
        } catch (GeneralSecurityException e) {
            throw new AeadAuthException("AEAD open failed", e);
        }
    }

    public byte[] randomNonce() {
        byte[] n = new byte[CHACHA_NONCE_LEN];
        random.nextBytes(n);
        return n;
    }

    // ---------- Ed25519 identity signature ----------

    public KeyPair generateIdentityKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("Ed25519");
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 unavailable", e);
        }
    }

    public byte[] encodeEd25519Pub(PublicKey pub) {
        if (!(pub instanceof EdECPublicKey ed)) {
            throw new IllegalArgumentException("not an Ed25519 public key");
        }
        EdECPoint pt = ed.getPoint();
        byte[] yBe = pt.getY().toByteArray();
        byte[] out = new byte[ED25519_KEY_LEN];
        // Reverse to little-endian, pad if needed.
        int copyLen = Math.min(yBe.length, ED25519_KEY_LEN);
        for (int i = 0; i < copyLen; i++) {
            out[i] = yBe[yBe.length - 1 - i];
        }
        if (pt.isXOdd()) out[ED25519_KEY_LEN - 1] |= (byte) 0x80;
        return out;
    }

    public PublicKey decodeEd25519Pub(byte[] raw) {
        if (raw == null || raw.length != ED25519_KEY_LEN) {
            throw new IllegalArgumentException("Ed25519 pubkey must be 32 bytes");
        }
        try {
            byte[] copy = raw.clone();
            boolean xOdd = (copy[ED25519_KEY_LEN - 1] & 0x80) != 0;
            copy[ED25519_KEY_LEN - 1] &= 0x7F;
            // Reverse to big-endian for BigInteger.
            byte[] be = new byte[ED25519_KEY_LEN];
            for (int i = 0; i < ED25519_KEY_LEN; i++) {
                be[i] = copy[ED25519_KEY_LEN - 1 - i];
            }
            java.math.BigInteger y = new java.math.BigInteger(1, be);
            EdECPoint pt = new EdECPoint(xOdd, y);
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            return kf.generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, pt));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 decode failed", e);
        }
    }

    public byte[] signEd25519(PrivateKey priv, byte[] message) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(priv);
            sig.update(message);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 sign failed", e);
        }
    }

    public boolean verifyEd25519(PublicKey pub, byte[] message, byte[] signature) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(pub);
            sig.update(message);
            return sig.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** Decode an X.509 SubjectPublicKeyInfo (used by JDK serialization round-trip in tests). */
    public PublicKey decodeX509(String alg, byte[] x509) {
        try {
            return KeyFactory.getInstance(alg).generatePublic(new X509EncodedKeySpec(x509));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(alg + " X.509 decode failed", e);
        }
    }

    // ---------- Aliases ----------

    /** Stable opaque alias for a device — first 16 bytes of SHA-256(identityPub), hex. */
    public String deviceAlias(byte[] identityPub) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(identityPub);
            return "dev-" + HexFormat.of().formatHex(h, 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ---------- Base64 helpers (URL-safe, no padding) ----------

    public static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static byte[] unb64(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    /** Thrown when AEAD tag verification fails — wire data was tampered with. */
    public static final class AeadAuthException extends Exception {
        public AeadAuthException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
