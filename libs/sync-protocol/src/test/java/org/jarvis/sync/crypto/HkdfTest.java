package org.jarvis.sync.crypto;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC 5869 §A.1 HKDF-SHA256 test vector — proves the implementation
 * matches the specification.
 */
class HkdfTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void rfc5869TestCase1() {
        byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = HEX.parseHex("000102030405060708090a0b0c");
        byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");
        byte[] expectedPrk = HEX.parseHex(
                "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5");
        byte[] expectedOkm = HEX.parseHex(
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                        + "34007208d5b887185865");

        byte[] prk = Hkdf.extract(salt, ikm);
        assertThat(prk).isEqualTo(expectedPrk);

        byte[] okm = Hkdf.expand(prk, info, 42);
        assertThat(okm).isEqualTo(expectedOkm);
    }

    @Test
    void deriveCombinesExtractAndExpand() {
        byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = HEX.parseHex("000102030405060708090a0b0c");
        byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");
        byte[] okm = Hkdf.derive(salt, ikm, info, 42);
        assertThat(okm).hasSize(42);
    }

    @Test
    void lengthBoundsEnforced() {
        byte[] prk = new byte[32];
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Hkdf.expand(prk, null, 0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Hkdf.expand(prk, null, 255 * 32 + 1));
    }
}
