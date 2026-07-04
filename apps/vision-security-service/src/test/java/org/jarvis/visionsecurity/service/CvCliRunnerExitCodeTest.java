package org.jarvis.visionsecurity.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exit-code contract for the headless CLI.
 * {@code 0} success · {@code 2} OCR engine · {@code 3} file missing ·
 * {@code 4} other · {@code 5} ask-screen with no VLM answer.
 */
class CvCliRunnerExitCodeTest {

    @Test
    void successNonAskIsZero() {
        assertThat(CvCliRunner.exitCodeFor(true, null, false, null)).isZero();
    }

    @Test
    void ocrEngineUnavailableIsTwo() {
        assertThat(CvCliRunner.exitCodeFor(false,
                "OCR engine unavailable: tesseract not on PATH", false, null)).isEqualTo(2);
    }

    @Test
    void missingInputFileIsThree() {
        assertThat(CvCliRunner.exitCodeFor(false,
                "Image file not found: /tmp/nope.png", false, null)).isEqualTo(3);
    }

    @Test
    void genericFailureIsFour() {
        assertThat(CvCliRunner.exitCodeFor(false, "screenshot capture failed", false, null))
                .isEqualTo(4);
    }

    @Test
    void askScreenReadyVlmIsZero() {
        assertThat(CvCliRunner.exitCodeFor(true, null, true, "READY")).isZero();
    }

    @Test
    void askScreenNotConfiguredVlmIsFive() {
        assertThat(CvCliRunner.exitCodeFor(true, null, true, "NOT_CONFIGURED")).isEqualTo(5);
    }

    @Test
    void askScreenUnavailableVlmIsFive() {
        assertThat(CvCliRunner.exitCodeFor(true, null, true, "UNAVAILABLE")).isEqualTo(5);
    }

    @Test
    void askScreenFailureStillUsesErrorCodes() {
        assertThat(CvCliRunner.exitCodeFor(false,
                "OCR engine unavailable", true, "UNAVAILABLE")).isEqualTo(2);
    }
}
