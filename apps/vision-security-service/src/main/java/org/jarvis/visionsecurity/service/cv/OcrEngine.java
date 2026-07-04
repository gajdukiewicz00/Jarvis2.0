package org.jarvis.visionsecurity.service.cv;

import org.jarvis.visionsecurity.model.CapabilityStatus;
import org.jarvis.visionsecurity.service.OcrService;

import java.nio.file.Path;

/**
 * Local OCR engine abstraction. Implementations must be 100% local (no
 * cloud APIs). Multiple engines can coexist; the active one is selected by
 * {@code vision-security.cv.engine} via {@link OcrEngineSelector}.
 */
public interface OcrEngine {

    /** Identifier exposed in {@code CvAnalysisResult.engine}. Stable. */
    String id();

    /** Health/availability of the underlying binary or library. */
    CapabilityStatus capabilityStatus();

    /**
     * Run OCR against an image and return per-line blocks with bounding
     * boxes and confidence, plus the resolved language. Throws
     * {@link OcrService.OcrUnavailableException} when the engine is not
     * installed, {@link OcrService.OcrExecutionException} for runtime
     * errors. No exceptions for empty results — empty blocks are allowed.
     */
    OcrService.StructuredOcrResult extractStructured(Path imagePath) throws Exception;
}
