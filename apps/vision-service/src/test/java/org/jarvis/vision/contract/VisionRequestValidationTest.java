package org.jarvis.vision.contract;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.jarvis.common.vision.VisionOwnerReferenceEnrollRequest;
import org.jarvis.common.vision.VisionScreenAnalysisRequest;
import org.jarvis.common.vision.VisionVerifyOwnerRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VisionRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void verifyOwnerRequestRejectsEmptyPayloadAndUnsupportedFormat() {
        VisionVerifyOwnerRequest request = new VisionVerifyOwnerRequest(
                new byte[0],
                "gif",
                "pc-control/webcam",
                "req-1",
                Map.of());

        var violations = validator.validate(request);

        assertThat(violations).hasSize(2);
    }

    @Test
    void enrollRequestRejectsOversizedLabel() {
        VisionOwnerReferenceEnrollRequest request = new VisionOwnerReferenceEnrollRequest(
                "x".repeat(81),
                new byte[]{1},
                "png",
                "req-2",
                Map.of());

        var violations = validator.validate(request);

        assertThat(violations).hasSize(1);
    }

    @Test
    void screenAnalysisRequestRejectsEmptyPayloadAndUnsupportedFormat() {
        VisionScreenAnalysisRequest request = new VisionScreenAnalysisRequest(
                new byte[0],
                "webp",
                "pc-control/screenshot",
                "req-screen",
                Map.of());

        var violations = validator.validate(request);

        assertThat(violations).hasSize(2);
    }
}
