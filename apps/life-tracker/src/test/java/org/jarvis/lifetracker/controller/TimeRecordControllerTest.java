package org.jarvis.lifetracker.controller;

import org.jarvis.lifetracker.domain.ActiveTimeRecord;
import org.jarvis.lifetracker.domain.TimeRecord;
import org.jarvis.lifetracker.dto.TimeRecordDTO;
import org.jarvis.lifetracker.repository.ActiveTimeRecordRepository;
import org.jarvis.lifetracker.repository.TimeRecordRepository;
import org.jarvis.lifetracker.service.DTOMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TimeRecordController.class)
@AutoConfigureMockMvc(addFilters = false)
class TimeRecordControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TimeRecordRepository timeRecordRepository;

    @MockBean
    private ActiveTimeRecordRepository activeTimeRecordRepository;

    @MockBean
    private DTOMapper dtoMapper;

    @Test
    void startTimerWithoutUserIdReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/life/time/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"activity": "Coding", "category": "Work"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void startTimerWithNoExistingActiveRecordCreatesOne() throws Exception {
        when(activeTimeRecordRepository.findByUserId("user-1")).thenReturn(Optional.empty());
        when(activeTimeRecordRepository.save(any(ActiveTimeRecord.class))).thenAnswer(inv -> {
            ActiveTimeRecord record = inv.getArgument(0);
            record.setId(1L);
            return record;
        });

        mockMvc.perform(post("/api/v1/life/time/start")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"activity": "Coding", "category": "Work"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activity").value("Coding"))
                .andExpect(jsonPath("$.category").value("Work"));

        verify(activeTimeRecordRepository).save(any(ActiveTimeRecord.class));
    }

    @Test
    void startTimerWithSameActivityAlreadyRunningReturnsExistingWithoutSaving() throws Exception {
        ActiveTimeRecord existing = new ActiveTimeRecord();
        existing.setId(5L);
        existing.setUserId("user-1");
        existing.setActivity("Coding");
        existing.setCategory("Work");
        existing.setStartTime(LocalDateTime.of(2026, 3, 10, 9, 0));
        when(activeTimeRecordRepository.findByUserId("user-1")).thenReturn(Optional.of(existing));

        mockMvc.perform(post("/api/v1/life/time/start")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"activity": "Coding", "category": "Work"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activity").value("Coding"));

        verify(activeTimeRecordRepository, never()).save(any(ActiveTimeRecord.class));
    }

    @Test
    void startTimerWithDifferentActivityAlreadyRunningReturnsConflict() throws Exception {
        ActiveTimeRecord existing = new ActiveTimeRecord();
        existing.setId(5L);
        existing.setUserId("user-1");
        existing.setActivity("Reading");
        existing.setCategory("Leisure");
        existing.setStartTime(LocalDateTime.of(2026, 3, 10, 9, 0));
        when(activeTimeRecordRepository.findByUserId("user-1")).thenReturn(Optional.of(existing));

        mockMvc.perform(post("/api/v1/life/time/start")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"activity": "Coding", "category": "Work"}
                                """))
                .andExpect(status().isConflict());

        verify(activeTimeRecordRepository, never()).save(any(ActiveTimeRecord.class));
    }

    @Test
    void stopTimerWithoutUserIdReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/life/time/stop"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void stopTimerWithNoActiveRecordAndNoHistoryReturnsNoContent() throws Exception {
        when(activeTimeRecordRepository.findByUserId("user-1")).thenReturn(Optional.empty());
        when(timeRecordRepository.findTopByUserIdOrderByEndTimeDesc("user-1")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/life/time/stop").header("X-User-Id", "user-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void stopTimerWithNoActiveRecordReturnsLastHistoricalRecord() throws Exception {
        TimeRecord lastRecord = new TimeRecord();
        lastRecord.setId(2L);
        lastRecord.setActivity("Reading");
        when(activeTimeRecordRepository.findByUserId("user-1")).thenReturn(Optional.empty());
        when(timeRecordRepository.findTopByUserIdOrderByEndTimeDesc("user-1")).thenReturn(Optional.of(lastRecord));
        when(dtoMapper.toDTO(lastRecord)).thenReturn(new TimeRecordDTO(2L, "Reading", null, null, null, null));

        mockMvc.perform(post("/api/v1/life/time/stop").header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activity").value("Reading"));
    }

    @Test
    void stopTimerWithActiveRecordStopsSavesAndDeletesActive() throws Exception {
        ActiveTimeRecord active = new ActiveTimeRecord();
        active.setId(7L);
        active.setUserId("user-1");
        active.setActivity("Coding");
        active.setCategory("Work");
        active.setStartTime(LocalDateTime.now().minusMinutes(30));
        when(activeTimeRecordRepository.findByUserId("user-1")).thenReturn(Optional.of(active));
        when(timeRecordRepository.save(any(TimeRecord.class))).thenAnswer(inv -> {
            TimeRecord record = inv.getArgument(0);
            record.setId(10L);
            return record;
        });
        when(dtoMapper.toDTO(any(TimeRecord.class)))
                .thenReturn(new TimeRecordDTO(10L, "Coding", "Work", null, null, 1800L));

        mockMvc.perform(post("/api/v1/life/time/stop").header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activity").value("Coding"))
                .andExpect(jsonPath("$.durationSeconds").value(1800));

        verify(timeRecordRepository).save(any(TimeRecord.class));
        verify(activeTimeRecordRepository).deleteById(7L);
    }

    @Test
    void getRecordsWithoutUserIdReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/life/time/records"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getRecordsReturnsMappedRecordsForUser() throws Exception {
        TimeRecord record = new TimeRecord();
        record.setId(3L);
        when(timeRecordRepository.findByUserIdOrderByStartTimeDesc("user-1")).thenReturn(List.of(record));
        when(dtoMapper.toDTO(record)).thenReturn(new TimeRecordDTO(3L, "Coding", "Work", null, null, null));

        mockMvc.perform(get("/api/v1/life/time/records").header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(3));
    }
}
