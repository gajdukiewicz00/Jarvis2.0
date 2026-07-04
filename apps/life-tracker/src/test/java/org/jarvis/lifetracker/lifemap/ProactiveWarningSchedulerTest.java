package org.jarvis.lifetracker.lifemap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProactiveWarningSchedulerTest {

    @Mock
    private DailySummaryService summaryService;
    @Mock
    private RestTemplate restTemplate;

    private ProactiveWarningScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ProactiveWarningScheduler(summaryService, restTemplate);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "usersCsv", "owner");
        ReflectionTestUtils.setField(scheduler, "voiceGatewayUrl", "http://voice-gateway:8081");
        ReflectionTestUtils.setField(scheduler, "language", "en-US");
        ReflectionTestUtils.setField(scheduler, "minGapSeconds", 1800L);
        // quietStartHour/quietEndHour left at default 0/0 => inQuietHours() always false
    }

    private LifeMapDtos.ProactiveWarning warning(String code) {
        return new LifeMapDtos.ProactiveWarning(
                "warn-1", code, LifeMapDtos.ProactiveWarning.Severity.WARN, "message for " + code,
                Map.of(), java.time.Instant.now());
    }

    private LifeMapDtos.DailySummary summaryWithWarnings(List<LifeMapDtos.ProactiveWarning> warnings) {
        return new LifeMapDtos.DailySummary(
                LocalDate.now(), 0L, Map.of(), null, null, null, 0, 0, null, 0, 0, warnings);
    }

    @Test
    void tickDoesNothingWhenDisabled() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);

        scheduler.tick();

        verifyNoSummaryLookup();
    }

    @Test
    void tickDoesNothingDuringQuietHours() {
        ReflectionTestUtils.setField(scheduler, "quietStartHour", 0);
        ReflectionTestUtils.setField(scheduler, "quietEndHour", 24);

        scheduler.tick();

        verifyNoSummaryLookup();
    }

    @Test
    void tickSkipsBlankUserIdsInCsv() {
        ReflectionTestUtils.setField(scheduler, "usersCsv", "owner, ,");
        when(summaryService.summarise(eq("owner"), any(LocalDate.class)))
                .thenReturn(summaryWithWarnings(List.of()));

        scheduler.tick();

        verify(summaryService, times(1)).summarise(eq("owner"), any(LocalDate.class));
    }

    @Test
    void tickSpeaksNewWarningAndRecordsLastSpokeTime() {
        when(summaryService.summarise(eq("owner"), any(LocalDate.class)))
                .thenReturn(summaryWithWarnings(List.of(warning("TIME_WASTE"))));
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));

        scheduler.tick();

        verify(restTemplate).postForEntity(
                eq("http://voice-gateway:8081/internal/voice/notify"), any(), eq(String.class));
    }

    @Test
    void tickDoesNotRepeatSameWarningWithinMinGap() {
        when(summaryService.summarise(eq("owner"), any(LocalDate.class)))
                .thenReturn(summaryWithWarnings(List.of(warning("TIME_WASTE"))));
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));

        scheduler.tick();
        scheduler.tick();

        verify(restTemplate, times(1)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void tickContinuesWhenSummaryLookupThrows() {
        when(summaryService.summarise(eq("owner"), any(LocalDate.class)))
                .thenThrow(new RuntimeException("boom"));

        scheduler.tick();

        verify(restTemplate, never()).postForEntity(anyString(), any(), any());
    }

    @Test
    void tickHandlesMultipleUsersFromCsv() {
        ReflectionTestUtils.setField(scheduler, "usersCsv", "owner,guest");
        when(summaryService.summarise(eq("owner"), any(LocalDate.class)))
                .thenReturn(summaryWithWarnings(List.of()));
        when(summaryService.summarise(eq("guest"), any(LocalDate.class)))
                .thenReturn(summaryWithWarnings(List.of()));

        scheduler.tick();

        verify(summaryService).summarise(eq("owner"), any(LocalDate.class));
        verify(summaryService).summarise(eq("guest"), any(LocalDate.class));
    }

    @Test
    void notifyVoiceSkipsBlankMessages() {
        when(summaryService.summarise(eq("owner"), any(LocalDate.class)))
                .thenReturn(summaryWithWarnings(List.of(new LifeMapDtos.ProactiveWarning(
                        "warn-2", "OVERSPEND", LifeMapDtos.ProactiveWarning.Severity.WARN, "  ",
                        Map.of(), java.time.Instant.now()))));

        scheduler.tick();

        verify(restTemplate, never()).postForEntity(anyString(), any(), any());
    }

    @Test
    void notifyVoiceReturnsFalseWhenUserNotConnected() {
        when(summaryService.summarise(eq("owner"), any(LocalDate.class)))
                .thenReturn(summaryWithWarnings(List.of(warning("LOW_SLEEP"))));
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null));

        scheduler.tick();
        // Since notifyVoice returned false, lastSpoke was not recorded, so a second tick tries again.
        scheduler.tick();

        verify(restTemplate, times(2)).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void notifyVoiceSwallowsRestClientExceptions() {
        when(summaryService.summarise(eq("owner"), any(LocalDate.class)))
                .thenReturn(summaryWithWarnings(List.of(warning("TIME_WASTE"))));
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("connect timed out"));

        scheduler.tick();

        verify(restTemplate).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void inQuietHoursHandlesWrapAroundRange() {
        ReflectionTestUtils.setField(scheduler, "quietStartHour", 23);
        ReflectionTestUtils.setField(scheduler, "quietEndHour", 8);
        int currentHour = LocalTime.now().getHour();
        boolean expectedQuiet = currentHour >= 23 || currentHour < 8;

        scheduler.tick();

        if (expectedQuiet) {
            verifyNoSummaryLookup();
        } else {
            verify(summaryService).summarise(eq("owner"), any(LocalDate.class));
        }
    }

    private void verifyNoSummaryLookup() {
        verify(summaryService, never()).summarise(anyString(), any(LocalDate.class));
    }
}
