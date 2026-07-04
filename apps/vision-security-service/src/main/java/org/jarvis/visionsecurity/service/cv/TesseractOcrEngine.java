package org.jarvis.visionsecurity.service.cv;

import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.jarvis.visionsecurity.service.OcrService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Thin adapter that exposes the existing tesseract-backed OCR pipeline via
 * the {@link OcrEngine} interface. The actual implementation already lives
 * in {@link OcrService} — this class only changes the dispatch surface so
 * additional engines (PaddleOCR, EasyOCR, …) can plug in without touching
 * {@code LocalCvService}.
 */
@Component
public class TesseractOcrEngine implements OcrEngine {

    private final OcrService ocrService;

    public TesseractOcrEngine(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    @Override
    public String id() {
        return OcrService.ENGINE; // "tesseract"
    }

    @Override
    public CapabilityStatus capabilityStatus() {
        return ocrService.capabilityStatus();
    }

    @Override
    public OcrService.StructuredOcrResult extractStructured(Path imagePath) throws Exception {
        return ocrService.extractStructured(imagePath);
    }
}
