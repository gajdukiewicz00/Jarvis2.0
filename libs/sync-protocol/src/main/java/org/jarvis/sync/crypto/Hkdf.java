package org.jarvis.sync.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Phase 12 — HKDF-SHA256 (RFC 5869) extract-and-expand.
 *
 * <p>Tiny self-contained implementation so the sync-protocol module
 * stays pure JDK (no BouncyCastle).</p>
 */
public final class Hkdf {

    private static final String HMAC = "HmacSHA256";
    private static final int HASH_LEN = 32;

    private Hkdf() {}

    /** RFC 5869 §2.2 extract step. {@code salt} may be null (treated as zero-filled). */
    public static byte[] extract(byte[] salt, byte[] inputKeyMaterial) {
        try {
            byte[] saltBytes = salt != null ? salt : new byte[HASH_LEN];
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(saltBytes, HMAC));
            return mac.doFinal(inputKeyMaterial);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** RFC 5869 §2.3 expand step. {@code length} must be ≤ 255 * 32. */
    public static byte[] expand(byte[] prk, byte[] info, int length) {
        if (length < 1 || length > 255 * HASH_LEN) {
            throw new IllegalArgumentException("length out of range: " + length);
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(prk, HMAC));
            byte[] infoBytes = info != null ? info : new byte[0];
            byte[] out = new byte[length];
            byte[] t = new byte[0];
            int written = 0;
            byte counter = 1;
            while (written < length) {
                mac.reset();
                mac.update(t);
                mac.update(infoBytes);
                mac.update(counter);
                t = mac.doFinal();
                int take = Math.min(t.length, length - written);
                System.arraycopy(t, 0, out, written, take);
                written += take;
                counter++;
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Convenience: extract+expand. */
    public static byte[] derive(byte[] salt, byte[] ikm, byte[] info, int length) {
        byte[] prk = extract(salt, ikm);
        try {
            return expand(prk, info, length);
        } finally {
            Arrays.fill(prk, (byte) 0);
        }
    }
}
