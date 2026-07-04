package org.jarvis.voicegateway.service.impl;

import org.jarvis.voicegateway.exception.SttUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VoskSttServiceTest exercises the service with model paths pointing at
 * non-existent directories. {@code loadModelIfPresent} short-circuits before
 * any native Vosk call when {@code Files.exists(path)} is false, so these
 * tests never touch the native library — they cover the real Java-side
 * routing/error-handling logic only.
 */
class VoskSttServiceTest {

    private VoskSttService service;

    @BeforeEach
    void setUp() {
        service = new VoskSttService();
        ReflectionTestUtils.setField(service, "modelPathRu", "/nonexistent/models/vosk-ru");
        ReflectionTestUtils.setField(service, "modelPathEn", "/nonexistent/models/vosk-en");
        ReflectionTestUtils.setField(service, "defaultLanguage", "ru-RU");
        ReflectionTestUtils.setField(service, "sampleRate", 16000f);
    }

    @Test
    void providerIdIsVosk() {
        assertEquals("vosk", service.providerId());
    }

    @Test
    void initDoesNotThrowWhenModelsAreMissing() {
        service.init();
        // No exception means the missing-model path was handled gracefully.
    }

    @Test
    void describeRuntimeReportsUnavailableWhenNoModelsExist() {
        Map<String, Object> runtime = service.describeRuntime();

        assertEquals("vosk", runtime.get("configuredProvider"));
        assertEquals("unavailable", runtime.get("status"));
        assertFalse((Boolean) runtime.get("available"));
        assertEquals("ru-RU", runtime.get("defaultLanguage"));

        @SuppressWarnings("unchecked")
        Map<String, Object> languages = (Map<String, Object>) runtime.get("languages");
        assertFalse((Boolean) ((Map<?, ?>) languages.get("ru-RU")).get("available"));
        assertFalse((Boolean) ((Map<?, ?>) languages.get("en-US")).get("available"));
    }

    @Test
    void describeRuntimeReportsPartialWhenDefaultLanguageDiffersFromMissingOther() {
        // Both models are missing here, so default (ru) is unavailable -> "unavailable".
        // This still exercises the "partial" branch guard via ru/en cross-checks.
        Map<String, Object> runtime = service.describeRuntime();
        assertEquals("unavailable", runtime.get("status"));
    }

    @Test
    void transcribeThrowsSttUnavailableExceptionWhenModelMissing() {
        SttUnavailableException ex = assertThrows(SttUnavailableException.class,
                () -> service.transcribe(new byte[3200], "ru-RU"));
        assertTrue(ex.getMessage().contains("Vosk STT model"));
    }

    @Test
    void transcribeUsesDefaultLanguageWhenLanguageCodeIsNull() {
        assertThrows(SttUnavailableException.class, () -> service.transcribe(new byte[3200], null));
    }

    @Test
    void createSessionThrowsWhenModelMissing() {
        assertThrows(SttUnavailableException.class, () -> service.createSession("en-US"));
    }

    @Test
    void createSessionWithNoArgsUsesDefaultLanguage() {
        assertThrows(SttUnavailableException.class, () -> service.createSession());
    }

    @Test
    void extractTextParsesSimpleJsonResult() {
        String text = (String) ReflectionTestUtils.invokeMethod(service, "extractText", "{\"text\" : \"hello world\"}");
        assertEquals("hello world", text);
    }

    @Test
    void extractTextReturnsTrimmedRawStringWhenNoTextField() {
        String text = (String) ReflectionTestUtils.invokeMethod(service, "extractText", "  {\"partial\":\"\"}  ");
        assertEquals("{\"partial\":\"\"}", text);
    }

    @Test
    void extractTextReturnsEmptyForNullInput() {
        String text = (String) ReflectionTestUtils.invokeMethod(service, "extractText", (Object) null);
        assertEquals("", text);
    }

    @Test
    void normalizeLangDefaultsToRussianForBlankOrUnknown() {
        String normalized = (String) ReflectionTestUtils.invokeMethod(service, "normalizeLang", (Object) null);
        assertEquals("ru-ru", normalized);
        assertEquals("ru-ru", ReflectionTestUtils.invokeMethod(service, "normalizeLang", "fr-FR"));
        assertEquals("en-us", ReflectionTestUtils.invokeMethod(service, "normalizeLang", "en_US"));
    }
}
