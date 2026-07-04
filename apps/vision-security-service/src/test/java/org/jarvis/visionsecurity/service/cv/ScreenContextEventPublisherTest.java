package org.jarvis.visionsecurity.service.cv;

import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CvAnalysisResult;
import org.jarvis.visionsecurity.model.ScreenContextResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.concurrent.SettableListenableFuture;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScreenContextEventPublisherTest {

    private final VisionSecurityProperties properties = new VisionSecurityProperties();

    @Test
    @SuppressWarnings("unchecked")
    void publishesJsonToConfiguredTopicWhenTemplateAvailable() {
        KafkaTemplate<String, String> template = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        ObjectProvider<KafkaTemplate<String, String>> provider = providerOf(template);
        ScreenContextEventPublisher publisher = new ScreenContextEventPublisher(provider, properties);
        ScreenContextResult result = sampleResult("owner", true);

        boolean published = publisher.publish(result);

        assertThat(published).isTrue();
        verify(template).send(eq("jarvis.cv.screen_context.created"), eq("owner"), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesAnonymousKeyWhenUserMissing() {
        KafkaTemplate<String, String> template = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        ObjectProvider<KafkaTemplate<String, String>> provider = providerOf(template);
        ScreenContextEventPublisher publisher = new ScreenContextEventPublisher(provider, properties);

        boolean published = publisher.publish(sampleResult(null, true));

        assertThat(published).isTrue();
        verify(template).send(eq("jarvis.cv.screen_context.created"), eq("anonymous"), anyString());
    }

    @Test
    void noOpWhenKafkaTemplateMissing() {
        ObjectProvider<KafkaTemplate<String, String>> provider = providerOf(null);
        ScreenContextEventPublisher publisher = new ScreenContextEventPublisher(provider, properties);

        assertThat(publisher.publish(sampleResult("owner", true))).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void noOpWhenDisabledByConfig() {
        properties.getCv().setPublishScreenContextEvent(false);
        KafkaTemplate<String, String> template = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        ObjectProvider<KafkaTemplate<String, String>> provider = providerOf(template);
        ScreenContextEventPublisher publisher = new ScreenContextEventPublisher(provider, properties);

        assertThat(publisher.publish(sampleResult("owner", true))).isFalse();
        verify(template, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void honoursCustomTopicFromConfig() {
        properties.getCv().setScreenContextTopic("jarvis.cv.custom-topic");
        KafkaTemplate<String, String> template = (KafkaTemplate<String, String>) mock(KafkaTemplate.class);
        when(template.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        ObjectProvider<KafkaTemplate<String, String>> provider = providerOf(template);
        ScreenContextEventPublisher publisher = new ScreenContextEventPublisher(provider, properties);

        publisher.publish(sampleResult("owner", true));

        verify(template).send(eq("jarvis.cv.custom-topic"), eq("owner"), anyString());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static ScreenContextResult sampleResult(String userId, boolean success) {
        CvAnalysisResult analysis = new CvAnalysisResult(
                "screenshot", "/tmp/x.png", 800, 600,
                "hi", List.of(), "tesseract", "eng", 5L,
                Instant.parse("2026-05-25T10:00:00Z"),
                success, success ? null : "fail");
        ScreenContextResult.VlmSection vlm = new ScreenContextResult.VlmSection(
                "NOT_CONFIGURED", "local-vlm-not-configured", null, "x");
        return new ScreenContextResult(
                userId, Instant.parse("2026-05-25T10:00:01Z"), 9L,
                "/tmp/x.png", "x11", "", "", List.of(), List.of(),
                List.of(), List.of(),
                new ScreenContextResult.DetectionSection("NOT_CONFIGURED", "NOT_CONFIGURED"),
                analysis, vlm, success, success ? null : "fail");
    }
}
