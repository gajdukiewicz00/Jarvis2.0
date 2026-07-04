package org.jarvis.lifetracker.controller;

import org.jarvis.lifetracker.dto.CalendarEventDTO;
import org.jarvis.lifetracker.service.CalendarService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CalendarController.class)
@AutoConfigureMockMvc(addFilters = false)
class CalendarControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CalendarService calendarService;

    @Test
    void addEventWithoutUserIdReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/life/calendar/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Standup", "startTime": "2026-03-10T09:00:00", "endTime": "2026-03-10T09:30:00"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void addEventDelegatesToCalendarServiceWithManualSource() throws Exception {
        CalendarEventDTO dto = new CalendarEventDTO();
        dto.setId(1L);
        dto.setTitle("Standup");
        when(calendarService.createEvent(any())).thenReturn(dto);

        mockMvc.perform(post("/api/v1/life/calendar/event")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Standup", "startTime": "2026-03-10T09:00:00", "endTime": "2026-03-10T09:30:00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Standup"));

        verify(calendarService).createEvent(any());
    }

    @Test
    void moveEventDelegatesToCalendarService() throws Exception {
        CalendarEventDTO dto = new CalendarEventDTO();
        dto.setId(11L);
        when(calendarService.moveEvent(eq("user-1"), eq(11L),
                eq(LocalDateTime.of(2026, 3, 10, 11, 0)), eq(LocalDateTime.of(2026, 3, 10, 11, 30))))
                .thenReturn(dto);

        mockMvc.perform(put("/api/v1/life/calendar/event/11")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newStartTime": "2026-03-10T11:00:00", "newEndTime": "2026-03-10T11:30:00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(11));
    }

    @Test
    void moveEventWithoutUserIdReturnsUnauthorized() throws Exception {
        mockMvc.perform(put("/api/v1/life/calendar/event/11")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newStartTime": "2026-03-10T11:00:00", "newEndTime": "2026-03-10T11:30:00"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getEventsDelegatesToCalendarService() throws Exception {
        when(calendarService.listEvents(eq("user-1"), any(), any())).thenReturn(List.of(new CalendarEventDTO()));

        mockMvc.perform(get("/api/v1/life/calendar/events").header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void getEventsWithoutUserIdReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/life/calendar/events"))
                .andExpect(status().isUnauthorized());
    }
}
