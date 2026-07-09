package org.jarvis.voicegateway.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VoiceTextNormalizerTest {

    @Test
    void normalizesVsCodeSttVariants() {
        assertEquals("открой vs code", VoiceTextNormalizer.applyAliases("открой вес скотт"));
        assertEquals("открой vs code", VoiceTextNormalizer.applyAliases("открой вес код"));
        assertEquals("открой vs code", VoiceTextNormalizer.applyAliases("открой виз код"));
    }

    @Test
    void normalizesTelegramDoubledLetters() {
        assertEquals("открой телеграм", VoiceTextNormalizer.applyAliases("открой телеграмм"));
    }

    @Test
    void normalizesMinimizeWindowsSttVariants() {
        assertEquals("сверни все окна", VoiceTextNormalizer.applyAliases("с кровь все окна"));
        assertEquals("сверни все окна", VoiceTextNormalizer.applyAliases("скорой все окна"));
        assertEquals("сверни все окна", VoiceTextNormalizer.applyAliases("убери все окна"));
    }

    @Test
    void leavesUnrelatedTextUnchanged() {
        assertEquals("какие планы на день", VoiceTextNormalizer.applyAliases("какие планы на день"));
        assertEquals("открой терминал", VoiceTextNormalizer.applyAliases("открой терминал"));
    }

    @Test
    void handlesNullAndBlank() {
        assertEquals(null, VoiceTextNormalizer.applyAliases(null));
        assertEquals("", VoiceTextNormalizer.applyAliases(""));
    }
}
