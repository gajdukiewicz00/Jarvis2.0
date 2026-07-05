package org.jarvis.media.config;

import org.jarvis.media.asr.AsrProvider;
import org.jarvis.media.asr.MockAsrProvider;
import org.jarvis.media.asr.WhisperCppAsrProvider;
import org.jarvis.media.asr.WhisperJsonParser;
import org.jarvis.media.process.ProcessRunner;
import org.jarvis.media.subtitle.LlmTranslationProvider;
import org.jarvis.media.subtitle.MediaTextGuard;
import org.jarvis.media.subtitle.MockTranslationProvider;
import org.jarvis.media.subtitle.TranslationProvider;
import org.jarvis.media.support.MediaTestFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Companion to {@link MediaProviderSelectionTest} (which only proves the mock
 * providers wire up in isolation) — this one scans the actual "real" flagged
 * providers added in Increment F, proving they compile, wire, and activate
 * correctly via {@code @ConditionalOnProperty} rather than only being reachable in
 * a hand-built unit test. Collaborator beans (ProcessRunner, WhisperJsonParser,
 * MediaTextGuard, RestTemplate, MediaProperties) are supplied manually since this
 * is a synthetic, minimal context — not a full {@code @SpringBootTest}.
 */
class RealMediaProviderSelectionTest {

    @Configuration
    @ComponentScan(
            basePackageClasses = {MockAsrProvider.class, WhisperCppAsrProvider.class,
                    MockTranslationProvider.class, LlmTranslationProvider.class},
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {MockAsrProvider.class, WhisperCppAsrProvider.class,
                            MockTranslationProvider.class, LlmTranslationProvider.class}
            )
    )
    static class RealProviderScanConfig {

        @Bean
        ProcessRunner processRunner() {
            return new ProcessRunner();
        }

        @Bean
        WhisperJsonParser whisperJsonParser() {
            return new WhisperJsonParser();
        }

        @Bean
        MediaTextGuard mediaTextGuard() {
            return new MediaTextGuard();
        }

        @Bean
        RestTemplate restTemplate() {
            return new RestTemplate();
        }

        @Bean
        MediaProperties mediaProperties() {
            return MediaTestFactory.props(Path.of(System.getProperty("java.io.tmpdir")));
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RealProviderScanConfig.class);

    @Test
    @DisplayName("no property set -> mock ASR/translation remain the only beans (mock stays default)")
    void defaultMode_stillActivatesOnlyMockProviders() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(AsrProvider.class)).hasSize(1);
            assertThat(context.getBean(AsrProvider.class)).isInstanceOf(MockAsrProvider.class);
            assertThat(context.getBeansOfType(TranslationProvider.class)).hasSize(1);
            assertThat(context.getBean(TranslationProvider.class)).isInstanceOf(MockTranslationProvider.class);
        });
    }

    @Test
    @DisplayName("media.asr.mode=whisper -> WhisperCppAsrProvider is the sole AsrProvider bean")
    void whisperMode_activatesWhisperCppAsrProviderOnly() {
        runner.withPropertyValues("media.asr.mode=whisper").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(AsrProvider.class)).hasSize(1);
            assertThat(context.getBean(AsrProvider.class)).isInstanceOf(WhisperCppAsrProvider.class);
        });
    }

    @Test
    @DisplayName("media.translation.mode=llm -> LlmTranslationProvider is the sole TranslationProvider bean")
    void llmMode_activatesLlmTranslationProviderOnly() {
        runner.withPropertyValues("media.translation.mode=llm").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(TranslationProvider.class)).hasSize(1);
            assertThat(context.getBean(TranslationProvider.class)).isInstanceOf(LlmTranslationProvider.class);
        });
    }
}
