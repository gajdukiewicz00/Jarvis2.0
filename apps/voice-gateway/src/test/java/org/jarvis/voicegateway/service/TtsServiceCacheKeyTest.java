package org.jarvis.voicegateway.service;

import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Finding #56 — {@code @Cacheable} on {@link TtsService#synthesize} must key on
 * speakingRate/pitch as well as text/languageCode/voiceName. Otherwise two calls that
 * differ only in speakingRate or pitch collide on the same cache entry and the second
 * call silently gets back audio synthesized at the first call's rate/pitch.
 *
 * <p>This evaluates the real SpEL key expression declared on the annotation (rather than
 * driving the method through a live Spring cache proxy), so it fails on the original
 * {@code key = "#text + '-' + #languageCode + '-' + #voiceName"} (identical key regardless
 * of rate/pitch) and passes once speakingRate/pitch are folded into the key.</p>
 */
class TtsServiceCacheKeyTest {

    @Test
    void cacheKeyDiffersWhenSpeakingRateDiffers() throws NoSuchMethodException {
        Expression keyExpression = synthesizeCacheKeyExpression();

        Object baseline = evaluateKey(keyExpression, "Привет", "ru-RU", "ru-RU-Wavenet-A", 1.0, 0.0);
        Object fasterRate = evaluateKey(keyExpression, "Привет", "ru-RU", "ru-RU-Wavenet-A", 2.0, 0.0);

        assertNotEquals(baseline, fasterRate,
                "cache key must vary with speakingRate, or a rate change returns stale cached audio");
    }

    @Test
    void cacheKeyDiffersWhenPitchDiffers() throws NoSuchMethodException {
        Expression keyExpression = synthesizeCacheKeyExpression();

        Object baseline = evaluateKey(keyExpression, "Привет", "ru-RU", "ru-RU-Wavenet-A", 1.0, 0.0);
        Object higherPitch = evaluateKey(keyExpression, "Привет", "ru-RU", "ru-RU-Wavenet-A", 1.0, 5.0);

        assertNotEquals(baseline, higherPitch,
                "cache key must vary with pitch, or a pitch change returns stale cached audio");
    }

    @Test
    void cacheKeyStillMatchesForIdenticalArguments() throws NoSuchMethodException {
        Expression keyExpression = synthesizeCacheKeyExpression();

        Object first = evaluateKey(keyExpression, "Привет", "ru-RU", "ru-RU-Wavenet-A", 1.0, 0.0);
        Object second = evaluateKey(keyExpression, "Привет", "ru-RU", "ru-RU-Wavenet-A", 1.0, 0.0);

        assertNotEquals(null, first);
        org.junit.jupiter.api.Assertions.assertEquals(first, second);
    }

    private static Expression synthesizeCacheKeyExpression() throws NoSuchMethodException {
        Method synthesize = TtsService.class.getMethod("synthesize",
                String.class, String.class, String.class, Double.class, Double.class);
        Cacheable cacheable = synthesize.getAnnotation(Cacheable.class);
        return new SpelExpressionParser().parseExpression(cacheable.key());
    }

    private static Object evaluateKey(Expression expression, String text, String languageCode,
            String voiceName, Double speakingRate, Double pitch) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariable("text", text);
        context.setVariable("languageCode", languageCode);
        context.setVariable("voiceName", voiceName);
        context.setVariable("speakingRate", speakingRate);
        context.setVariable("pitch", pitch);
        return expression.getValue(context);
    }
}
