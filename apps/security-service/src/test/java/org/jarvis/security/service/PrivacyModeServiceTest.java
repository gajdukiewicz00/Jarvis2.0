package org.jarvis.security.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the privacy/panic-mode flag. Small and safe by design:
 * verifies the enable/disable toggling and the "since" timestamp bookkeeping.
 */
class PrivacyModeServiceTest {

    @Test
    void startsInactiveWithNoSinceTimestamp() {
        PrivacyModeService service = new PrivacyModeService();

        assertThat(service.isActive()).isFalse();
        assertThat(service.since()).isNull();
    }

    @Test
    void enableActivatesAndRecordsTimestamp() {
        PrivacyModeService service = new PrivacyModeService();
        Instant before = Instant.now().minusSeconds(1);

        service.enable();

        assertThat(service.isActive()).isTrue();
        assertThat(service.since()).isNotNull();
        assertThat(service.since()).isAfterOrEqualTo(before);
    }

    @Test
    void enableTwiceKeepsOriginalTimestamp() {
        PrivacyModeService service = new PrivacyModeService();
        service.enable();
        Instant firstSince = service.since();

        service.enable();

        assertThat(service.isActive()).isTrue();
        assertThat(service.since()).isEqualTo(firstSince);
    }

    @Test
    void disableDeactivatesAndClearsTimestamp() {
        PrivacyModeService service = new PrivacyModeService();
        service.enable();

        service.disable();

        assertThat(service.isActive()).isFalse();
        assertThat(service.since()).isNull();
    }

    @Test
    void disableWhenAlreadyInactiveIsNoop() {
        PrivacyModeService service = new PrivacyModeService();

        service.disable();

        assertThat(service.isActive()).isFalse();
        assertThat(service.since()).isNull();
    }
}
