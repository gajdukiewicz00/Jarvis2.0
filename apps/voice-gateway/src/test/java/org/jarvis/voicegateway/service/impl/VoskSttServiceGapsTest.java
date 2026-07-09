package org.jarvis.voicegateway.service.impl;

import org.jarvis.voicegateway.exception.SttUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Supplements {@link VoskSttServiceTest} with the English-language routing and
 * the small private-helper branches (canonicalLanguage/describePath/pathForLanguage
 * and the missing-model "reason" detail) that the happy-path Russian default does
 * not exercise. Still never touches the native Vosk library — every model path
 * points at a non-existent directory.
 */
class VoskSttServiceGapsTest {

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
    void transcribeEnglishReportsCanonicalEnglishLanguageInError() {
        SttUnavailableException ex = assertThrows(SttUnavailableException.class,
                () -> service.transcribe(new byte[3200], "en-US"));
        assertTrue(ex.getMessage().contains("en-US"),
                "error should surface the canonical English language tag");
    }

    @Test
    void describeRuntimeExposesMissingModelReasonForEnglish() {
        Map<String, Object> runtime = service.describeRuntime();

        @SuppressWarnings("unchecked")
        Map<String, Object> languages = (Map<String, Object>) runtime.get("languages");
        @SuppressWarnings("unchecked")
        Map<String, Object> enDetails = (Map<String, Object>) languages.get("en-US");

        assertFalse((Boolean) enDetails.get("available"));
        assertFalse((Boolean) enDetails.get("pathExists"));
        assertEquals("Model is missing or failed to load", enDetails.get("reason"));
        assertTrue(String.valueOf(enDetails.get("modelPath")).contains("vosk-en"));
    }

    @Test
    void describeRuntimeReportsConfiguredSampleRate() {
        Map<String, Object> runtime = service.describeRuntime();
        assertEquals(16000f, runtime.get("sampleRate"));
    }

    @Test
    void canonicalLanguageMapsEnglishAndRussian() {
        assertEquals("en-US", ReflectionTestUtils.invokeMethod(service, "canonicalLanguage", "en-us"));
        assertEquals("ru-RU", ReflectionTestUtils.invokeMethod(service, "canonicalLanguage", "ru"));
    }

    @Test
    void describePathReturnsPlaceholderForNull() {
        assertEquals("<none>", ReflectionTestUtils.invokeMethod(service, "describePath", (Object) null));
    }

    @Test
    void pathForLanguageRoutesEnglishToEnglishModelPath() {
        Path path = ReflectionTestUtils.invokeMethod(service, "pathForLanguage", "en-US");
        assertTrue(path.toString().contains("vosk-en"));
    }
}
