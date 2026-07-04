package org.jarvis.security.controller;

import org.jarvis.security.service.PrivacyModeService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the privacy/panic-mode toggle endpoints. The service is
 * mocked so we can assert the controller both delegates the mutation and
 * shapes the status payload correctly.
 */
class PrivacyControllerTest {

    @Test
    void onEnablesPrivacyModeAndReturnsActiveStatus() {
        PrivacyModeService service = mock(PrivacyModeService.class);
        Instant since = Instant.parse("2026-01-01T00:00:00Z");
        when(service.isActive()).thenReturn(true);
        when(service.since()).thenReturn(since);
        PrivacyController controller = new PrivacyController(service);

        Map<String, Object> result = controller.on();

        verify(service).enable();
        assertThat(result.get("active")).isEqualTo(true);
        assertThat(result.get("since")).isEqualTo(since.toString());
    }

    @Test
    void offDisablesPrivacyModeAndReturnsInactiveStatus() {
        PrivacyModeService service = mock(PrivacyModeService.class);
        when(service.isActive()).thenReturn(false);
        when(service.since()).thenReturn(null);
        PrivacyController controller = new PrivacyController(service);

        Map<String, Object> result = controller.off();

        verify(service).disable();
        assertThat(result.get("active")).isEqualTo(false);
        assertThat(result.get("since")).isNull();
    }

    @Test
    void getReturnsCurrentStatusWithoutMutatingState() {
        PrivacyModeService service = mock(PrivacyModeService.class);
        when(service.isActive()).thenReturn(true);
        when(service.since()).thenReturn(null);
        PrivacyController controller = new PrivacyController(service);

        Map<String, Object> result = controller.get();

        assertThat(result.get("active")).isEqualTo(true);
        assertThat(result.get("since")).isNull();
    }
}
