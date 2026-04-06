package org.jarvis.voicegateway.config;

import org.jarvis.voicegateway.exception.SttUnavailableException;
import org.jarvis.voicegateway.service.SttService;
import org.jarvis.voicegateway.service.impl.NoOpSttService;
import org.jarvis.voicegateway.service.impl.VoskSttService;
import org.jarvis.voicegateway.service.impl.WhisperSttService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Smoke test: exactly one SttService bean must be created per jarvis.stt.provider value.
 * Verifies that NoUniqueBeanDefinitionException cannot occur.
 */
class SttProviderSelectionTest {

    @Configuration
    @ComponentScan(
            basePackageClasses = VoskSttService.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {VoskSttService.class, WhisperSttService.class, NoOpSttService.class}
            )
    )
    static class SttScanConfig {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SttScanConfig.class)
            .withPropertyValues(
                    "jarvis.vosk.model-path-ru=/tmp/nonexistent-vosk-ru",
                    "jarvis.vosk.model-path-en=/tmp/nonexistent-vosk-en",
                    "jarvis.vosk.default-language=ru-RU",
                    "jarvis.vosk.sample-rate=16000",
                    "jarvis.voice.whisper.model-path=/tmp/nonexistent-whisper.bin"
            );

    @Test
    @DisplayName("provider=vosk → only VoskSttService")
    void voskMode_activatesOnlyVosk() {
        runner.withPropertyValues("jarvis.stt.provider=vosk")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(SttService.class)).hasSize(1);
                    assertThat(context.getBean(SttService.class)).isInstanceOf(VoskSttService.class);
                    assertThat(context).doesNotHaveBean(WhisperSttService.class);
                    assertThat(context).doesNotHaveBean(NoOpSttService.class);
                });
    }

    @Test
    @DisplayName("provider=whisper → only WhisperSttService")
    void whisperMode_activatesOnlyWhisper() {
        runner.withPropertyValues("jarvis.stt.provider=whisper")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(SttService.class)).hasSize(1);
                    assertThat(context.getBean(SttService.class)).isInstanceOf(WhisperSttService.class);
                    assertThat(context).doesNotHaveBean(VoskSttService.class);
                    assertThat(context).doesNotHaveBean(NoOpSttService.class);
                });
    }

    @Test
    @DisplayName("provider=noop → only NoOpSttService")
    void noopMode_activatesOnlyNoOp() {
        runner.withPropertyValues("jarvis.stt.provider=noop")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(SttService.class)).hasSize(1);
                    assertThat(context.getBean(SttService.class)).isInstanceOf(NoOpSttService.class);
                    assertThat(context).doesNotHaveBean(VoskSttService.class);
                    assertThat(context).doesNotHaveBean(WhisperSttService.class);
                });
    }

    @Test
    @DisplayName("no property set → vosk by default (matchIfMissing=true)")
    void defaultMode_activatesVosk() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SttService.class);
            assertThat(context.getBean(SttService.class)).isInstanceOf(VoskSttService.class);
        });
    }

    @Test
    @DisplayName("unknown provider → zero SttService beans")
    void unknownProvider_activatesNone() {
        runner.withPropertyValues("jarvis.stt.provider=invalid")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(SttService.class)).isEmpty();
                });
    }

    @Test
    @DisplayName("noop: transcribe() throws SttUnavailableException")
    void noopMode_transcribeThrowsSttUnavailable() {
        runner.withPropertyValues("jarvis.stt.provider=noop")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SttService stt = context.getBean(SttService.class);
                    assertThatThrownBy(() -> stt.transcribe(new byte[0], "ru"))
                            .isInstanceOf(SttUnavailableException.class);
                });
    }

    @Test
    @DisplayName("noop: createSession() throws SttUnavailableException")
    void noopMode_createSessionThrowsSttUnavailable() {
        runner.withPropertyValues("jarvis.stt.provider=noop")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SttService stt = context.getBean(SttService.class);
                    assertThatThrownBy(stt::createSession)
                            .isInstanceOf(SttUnavailableException.class);
                });
    }

    @Test
    @DisplayName("vosk: missing models fail honestly instead of returning an empty transcript")
    void voskMode_missingModelsThrowSttUnavailable() {
        runner.withPropertyValues("jarvis.stt.provider=vosk")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SttService stt = context.getBean(SttService.class);
                    assertThatThrownBy(() -> stt.transcribe(new byte[] {0, 0}, "ru-RU"))
                            .isInstanceOf(SttUnavailableException.class);
                    assertThatThrownBy(() -> stt.createSession("en-US"))
                            .isInstanceOf(SttUnavailableException.class);
                });
    }

    @Test
    @DisplayName("whisper: missing model fails honestly instead of returning an empty transcript")
    void whisperMode_missingModelThrowsSttUnavailable() {
        runner.withPropertyValues("jarvis.stt.provider=whisper")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SttService stt = context.getBean(SttService.class);
                    assertThatThrownBy(() -> stt.transcribe(new byte[] {0, 0}, "ru-RU"))
                            .isInstanceOf(SttUnavailableException.class);
                    assertThatThrownBy(() -> stt.createSession("ru-RU"))
                            .isInstanceOf(SttUnavailableException.class);
                });
    }
}
