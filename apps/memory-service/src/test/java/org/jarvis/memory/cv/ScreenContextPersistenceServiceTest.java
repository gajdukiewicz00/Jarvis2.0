package org.jarvis.memory.cv;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jarvis.memory.service.EmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScreenContextPersistenceServiceTest {

    private ScreenContextObservationRepository repository;
    private EmbeddingClient embeddingClient;
    private ScreenContextProperties properties;
    private ScreenContextPersistenceService service;

    @BeforeEach
    void setUp() {
        repository = mock(ScreenContextObservationRepository.class);
        embeddingClient = mock(EmbeddingClient.class);
        properties = new ScreenContextProperties();
        service = new ScreenContextPersistenceService(
                repository, properties, embeddingClient, new SimpleMeterRegistry());
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private ScreenContextEvent sampleEvent(String screenshotPath) {
        return new ScreenContextEvent(
                "owner", Instant.parse("2026-05-25T10:00:00Z"), 1234L,
                screenshotPath, "x11", "Terminal", "gnome-terminal",
                List.of("DEV", "WORK"),
                List.of(),
                List.of(),
                new ScreenContextEvent.Analysis("hello world",
                        List.of(Map.of("text", "hello", "confidence", 95.0)),
                        "tesseract", "eng"),
                true, null);
    }

    @Test
    void persistsNewObservationWithMappedFields() {
        properties.setEmbed(false);
        properties.setStoreRawScreenshot(false);

        ScreenContextPersistenceService.Outcome outcome = service.persist(sampleEvent("/tmp/x.png"));

        assertThat(outcome).isEqualTo(ScreenContextPersistenceService.Outcome.PERSISTED);
        ArgumentCaptor<ScreenContextObservationEntity> captor =
                ArgumentCaptor.forClass(ScreenContextObservationEntity.class);
        verify(repository).save(captor.capture());
        ScreenContextObservationEntity e = captor.getValue();
        assertThat(e.getUserId()).isEqualTo("owner");
        assertThat(e.getOcrText()).isEqualTo("hello world");
        assertThat(e.getSemanticTags()).containsExactly("DEV", "WORK");
        assertThat(e.getOcrBlocks()).hasSize(1);
        assertThat(e.getEngine()).isEqualTo("tesseract");
        assertThat(e.getOcrLanguage()).isEqualTo("eng");
        assertThat(e.getScreenshotPath()).isEqualTo("/tmp/x.png");
        assertThat(e.getScreenshotBytes()).isNull();
        assertThat(e.getEmbedding()).isNull();
        assertThat(e.getIdempotencyKey()).isNotBlank();
    }

    @Test
    void duplicateEventIsNotPersistedAgain() {
        when(repository.existsByIdempotencyKey(anyString())).thenReturn(true);

        ScreenContextPersistenceService.Outcome outcome = service.persist(sampleEvent("/tmp/x.png"));

        assertThat(outcome).isEqualTo(ScreenContextPersistenceService.Outcome.DUPLICATE);
        verify(repository, never()).save(any());
    }

    @Test
    void storesRawScreenshotWhenReadableAndWithinCap(@TempDir Path tmp) throws Exception {
        Path png = tmp.resolve("shot.png");
        Files.write(png, new byte[] {1, 2, 3, 4, 5});
        properties.setStoreRawScreenshot(true);
        properties.setEmbed(false);

        service.persist(sampleEvent(png.toString()));

        ArgumentCaptor<ScreenContextObservationEntity> captor =
                ArgumentCaptor.forClass(ScreenContextObservationEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getScreenshotBytes()).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void skipsRawScreenshotWhenOverCap(@TempDir Path tmp) throws Exception {
        Path png = tmp.resolve("big.png");
        Files.write(png, new byte[100]);
        properties.setStoreRawScreenshot(true);
        properties.setMaxScreenshotBytes(10);
        properties.setEmbed(false);

        service.persist(sampleEvent(png.toString()));

        ArgumentCaptor<ScreenContextObservationEntity> captor =
                ArgumentCaptor.forClass(ScreenContextObservationEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getScreenshotBytes()).isNull();
    }

    @Test
    void storesEmbeddingWhenServiceAvailable() {
        properties.setEmbed(true);
        properties.setStoreRawScreenshot(false);
        List<Float> vec = new ArrayList<>();
        for (int i = 0; i < 384; i++) {
            vec.add(0.01f * i);
        }
        when(embeddingClient.embed(anyString(), anyString())).thenReturn(vec);

        service.persist(sampleEvent("/tmp/x.png"));

        ArgumentCaptor<ScreenContextObservationEntity> captor =
                ArgumentCaptor.forClass(ScreenContextObservationEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEmbedding()).hasSize(384);
        assertThat(captor.getValue().getEmbeddingModel())
                .isEqualTo(ScreenContextPersistenceService.EMBEDDING_MODEL);
    }

    @Test
    void degradesGracefullyWhenEmbeddingServiceDown() {
        properties.setEmbed(true);
        properties.setStoreRawScreenshot(false);
        when(embeddingClient.embed(anyString(), anyString()))
                .thenThrow(new RuntimeException("connection refused"));

        ScreenContextPersistenceService.Outcome outcome = service.persist(sampleEvent("/tmp/x.png"));

        assertThat(outcome).isEqualTo(ScreenContextPersistenceService.Outcome.PERSISTED);
        ArgumentCaptor<ScreenContextObservationEntity> captor =
                ArgumentCaptor.forClass(ScreenContextObservationEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEmbedding()).isNull();
    }

    @Test
    void idempotencyKeyIsDeterministicAndDependsOnInputs() {
        ScreenContextEvent a = sampleEvent("/tmp/x.png");
        ScreenContextEvent b = sampleEvent("/tmp/other.png");
        assertThat(ScreenContextPersistenceService.idempotencyKey(a))
                .isEqualTo(ScreenContextPersistenceService.idempotencyKey(a))
                .isNotEqualTo(ScreenContextPersistenceService.idempotencyKey(b));
    }
}
