package org.jarvis.userprofile.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EmotionTest {

    @Test
    void valuesContainsAllExpectedConstantsInDeclarationOrder() {
        assertArrayEquals(
                new Emotion[] {Emotion.NEUTRAL, Emotion.CALM, Emotion.ENERGETIC, Emotion.EMPATHETIC},
                Emotion.values());
    }

    @Test
    void valueOfReturnsMatchingConstant() {
        assertEquals(Emotion.CALM, Emotion.valueOf("CALM"));
        assertEquals(Emotion.EMPATHETIC, Emotion.valueOf("EMPATHETIC"));
    }
}
