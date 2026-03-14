package org.jarvis.llm.service;

import org.jarvis.llm.model.CommunicationStyle;
import org.jarvis.llm.model.Emotion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmotionSelectorTest {

    private EmotionSelector selector;

    @BeforeEach
    void setUp() {
        selector = new EmotionSelector();
    }

    @Test
    void selectEmotionPrefersEnergeticToneInTheMorning() {
        assertEquals(
                Emotion.ENERGETIC,
                selector.selectEmotion("просто проверка", LocalTime.of(8, 30), CommunicationStyle.FRIENDLY));
    }

    @Test
    void selectEmotionPrefersCalmToneLateAtNight() {
        assertEquals(
                Emotion.CALM,
                selector.selectEmotion("просто проверка", LocalTime.of(23, 15), CommunicationStyle.FRIENDLY));
    }

    @Test
    void selectEmotionDetectsStressKeywords() {
        assertEquals(
                Emotion.EMPATHETIC,
                selector.selectEmotion("я устал и у меня проблема", LocalTime.of(14, 0), CommunicationStyle.FRIENDLY));
    }

    @Test
    void selectEmotionFallsBackToStyleDefaults() {
        assertEquals(
                Emotion.CALM,
                selector.selectEmotion("покажи статус", LocalTime.of(14, 0), CommunicationStyle.CONCISE));
        assertEquals(
                Emotion.NEUTRAL,
                selector.selectEmotion("покажи статус", LocalTime.of(14, 0), CommunicationStyle.FORMAL));
    }
}
