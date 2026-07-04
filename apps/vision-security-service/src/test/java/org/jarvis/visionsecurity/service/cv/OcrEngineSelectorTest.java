package org.jarvis.visionsecurity.service.cv;

import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.jarvis.visionsecurity.service.OcrService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OcrEngineSelectorTest {

    private final VisionSecurityProperties properties = new VisionSecurityProperties();

    @Test
    void picksEngineMatchingConfiguredId() {
        OcrEngine tesseract = new StubEngine("tesseract");
        OcrEngine paddle = new StubEngine("paddleocr");
        properties.getCv().setEngine("paddleocr");

        OcrEngine selected = new OcrEngineSelector(List.of(tesseract, paddle), properties).select();

        assertThat(selected.id()).isEqualTo("paddleocr");
    }

    @Test
    void fallsBackToFirstEngineWhenConfiguredIdUnknown() {
        OcrEngine first = new StubEngine("tesseract");
        OcrEngine second = new StubEngine("easyocr");
        properties.getCv().setEngine("non-existent-engine");

        OcrEngine selected = new OcrEngineSelector(List.of(first, second), properties).select();

        assertThat(selected.id()).isEqualTo("tesseract");
    }

    @Test
    void defaultsToFirstWhenConfigIsBlank() {
        OcrEngine first = new StubEngine("tesseract");
        properties.getCv().setEngine("   ");

        assertThat(new OcrEngineSelector(List.of(first), properties).select().id())
                .isEqualTo("tesseract");
    }

    @Test
    void selectionIsCaseInsensitive() {
        OcrEngine first = new StubEngine("Tesseract");
        properties.getCv().setEngine("TESSERACT");

        assertThat(new OcrEngineSelector(List.of(first), properties).select().id())
                .isEqualTo("Tesseract");
    }

    @Test
    void throwsWhenNoEnginesRegistered() {
        assertThatThrownBy(() -> new OcrEngineSelector(List.of(), properties).select())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No OcrEngine");
    }

    private record StubEngine(String id) implements OcrEngine {
        @Override
        public CapabilityStatus capabilityStatus() {
            return new CapabilityStatus("AVAILABLE", id);
        }
        @Override
        public OcrService.StructuredOcrResult extractStructured(Path imagePath) {
            return new OcrService.StructuredOcrResult(List.of(), "", "eng");
        }
    }
}
