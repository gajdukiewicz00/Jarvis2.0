package org.jarvis.media.config;

import org.jarvis.media.asr.AsrProvider;
import org.jarvis.media.asr.MockAsrProvider;
import org.jarvis.media.subtitle.MockTranslationProvider;
import org.jarvis.media.subtitle.TranslationProvider;
import org.jarvis.media.tts.NeutralRussianTtsProvider;
import org.jarvis.media.tts.TtsProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: mirrors {@code SttProviderSelectionTest} in voice-gateway. Confirms the
 * mock ASR/translation/TTS providers are the default wiring (per README's documented
 * mock-MVP decision) and that an unrecognized provider mode fails closed to zero beans
 * rather than silently falling back to something unexpected — there are no "real"
 * ASR/translation/TTS beans registered yet, so any non-mock mode value must yield no
 * provider bean at all.
 */
class MediaProviderSelectionTest {

    @Configuration
    @ComponentScan(
            basePackageClasses = {MockAsrProvider.class, MockTranslationProvider.class, NeutralRussianTtsProvider.class},
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {MockAsrProvider.class, MockTranslationProvider.class, NeutralRussianTtsProvider.class}
            )
    )
    static class MediaProviderScanConfig {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MediaProviderScanConfig.class);

    @Test
    @DisplayName("no property set → mock ASR/translation/TTS by default (matchIfMissing=true)")
    void defaultMode_activatesMockProviders() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AsrProvider.class);
            assertThat(context.getBean(AsrProvider.class)).isInstanceOf(MockAsrProvider.class);
            assertThat(context).hasSingleBean(TranslationProvider.class);
            assertThat(context.getBean(TranslationProvider.class)).isInstanceOf(MockTranslationProvider.class);
            assertThat(context).hasSingleBean(TtsProvider.class);
            assertThat(context.getBean(TtsProvider.class)).isInstanceOf(NeutralRussianTtsProvider.class);
        });
    }

    @Test
    @DisplayName("mode=mock explicitly set → same mock providers")
    void explicitMockMode_activatesMockProviders() {
        runner.withPropertyValues(
                        "media.asr.mode=mock",
                        "media.translation.mode=mock",
                        "media.tts.mode=mock")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AsrProvider.class)).isInstanceOf(MockAsrProvider.class);
                    assertThat(context.getBean(TranslationProvider.class)).isInstanceOf(MockTranslationProvider.class);
                    assertThat(context.getBean(TtsProvider.class)).isInstanceOf(NeutralRussianTtsProvider.class);
                });
    }

    @Test
    @DisplayName("unrecognized provider mode → zero provider beans (fails closed, no silent fallback)")
    void unknownProviderMode_activatesNone() {
        runner.withPropertyValues(
                        "media.asr.mode=whisper",
                        "media.translation.mode=llm",
                        "media.tts.mode=piper")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(AsrProvider.class)).isEmpty();
                    assertThat(context.getBeansOfType(TranslationProvider.class)).isEmpty();
                    assertThat(context.getBeansOfType(TtsProvider.class)).isEmpty();
                });
    }
}
