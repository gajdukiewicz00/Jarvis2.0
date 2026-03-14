package org.jarvis.lifetracker.controller;

import org.jarvis.common.exception.IdempotencyConflictException;
import org.jarvis.lifetracker.dto.CalendarConflictDTO;
import org.jarvis.lifetracker.dto.CalendarEventDTO;
import org.jarvis.lifetracker.service.CalendarEventNotFoundException;
import org.jarvis.lifetracker.service.CalendarConflictException;
import org.jarvis.lifetracker.service.CalendarService;
import org.jarvis.lifetracker.tooling.ToolRequestService;
import org.jarvis.lifetracker.tooling.dto.CreateEventToolRequest;
import org.jarvis.lifetracker.tooling.dto.MoveEventToolRequest;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ToolCalendarController.class)
@AutoConfigureMockMvc(addFilters = false)
class ToolCalendarControllerNegativeWebSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CalendarService calendarService;

    @MockBean
    private ToolRequestService toolRequestService;

    @Test
    void createEventWithMissingTitleReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/tools/calendar/create")
                        .requestAttr("toolUserId", "user-123")
                        .header("X-Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "startTime": "2026-03-10T10:00:00",
                                  "endTime": "2026-03-10T11:00:00",
                                  "confirmed": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.details[0].field").value("title"));

        verifyNoInteractions(calendarService, toolRequestService);
    }

    @Test
    void createEventWithUnknownFieldReturnsInvalidPayload() throws Exception {
        mockMvc.perform(post("/api/v1/tools/calendar/create")
                        .requestAttr("toolUserId", "user-123")
                        .header("X-Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Planning",
                                  "startTime": "2026-03-10T10:00:00",
                                  "endTime": "2026-03-10T11:00:00",
                                  "confirmed": true,
                                  "rogue": "value"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_payload"))
                .andExpect(jsonPath("$.message").value("Unknown field: rogue"));

        verifyNoInteractions(calendarService, toolRequestService);
    }

    @Test
    void createEventWithIdempotencyConflictReturnsConflict() throws Exception {
        when(toolRequestService.hashRequest(any(CreateEventToolRequest.class))).thenReturn("hash-1");
        when(toolRequestService.loadCachedResponse(
                eq("key-1"),
                eq("create_event"),
                eq("user-123"),
                eq("hash-1"),
                eq(CalendarEventDTO.class)))
                .thenThrow(new IdempotencyConflictException("Idempotency key reused with different request payload"));

        mockMvc.perform(post("/api/v1/tools/calendar/create")
                        .requestAttr("toolUserId", "user-123")
                        .header("X-Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreatePayload()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("idempotency_conflict"))
                .andExpect(jsonPath("$.message").value("Idempotency key reused with different request payload"));

        verifyNoInteractions(calendarService);
        verify(toolRequestService, never()).storeResponse(any(), any(), any(), any(), any());
    }

    @Test
    void createEventWithCalendarConflictReturnsConflictDetails() throws Exception {
        when(toolRequestService.hashRequest(any(CreateEventToolRequest.class))).thenReturn("hash-1");
        when(toolRequestService.loadCachedResponse(
                eq("key-1"),
                eq("create_event"),
                eq("user-123"),
                eq("hash-1"),
                eq(CalendarEventDTO.class)))
                .thenReturn(Optional.empty());
        when(calendarService.createEvent(any()))
                .thenThrow(new CalendarConflictException("Calendar conflict detected", List.of(
                        new CalendarConflictDTO(
                                7L,
                                "Existing event",
                                LocalDateTime.of(2026, 3, 10, 10, 0),
                                LocalDateTime.of(2026, 3, 10, 11, 0),
                                false,
                                "Meeting room",
                                null))));

        mockMvc.perform(post("/api/v1/tools/calendar/create")
                        .requestAttr("toolUserId", "user-123")
                        .header("X-Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreatePayload()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("calendar_conflict"))
                .andExpect(jsonPath("$.conflicts[0].eventId").value(7L));

        verify(toolRequestService, never()).storeResponse(any(), any(), any(), any(), any());
    }

    @Test
    void moveEventForAnotherUsersEventReturnsNotFound() throws Exception {
        when(toolRequestService.hashRequest(any(MoveEventToolRequest.class))).thenReturn("hash-move");
        when(toolRequestService.loadCachedResponse(
                eq("key-2"),
                eq("move_event"),
                eq("user-123"),
                eq("hash-move"),
                eq(CalendarEventDTO.class)))
                .thenReturn(Optional.empty());
        when(calendarService.moveEvent(
                eq("user-123"),
                eq(99L),
                eq(LocalDateTime.of(2026, 3, 10, 12, 0)),
                eq(LocalDateTime.of(2026, 3, 10, 13, 0))))
                .thenThrow(new CalendarEventNotFoundException("user-123", 99L));

        mockMvc.perform(post("/api/v1/tools/calendar/move")
                        .requestAttr("toolUserId", "user-123")
                        .header("X-Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": 99,
                                  "newStartTime": "2026-03-10T12:00:00",
                                  "newEndTime": "2026-03-10T13:00:00",
                                  "confirmed": true
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("calendar_event_not_found"))
                .andExpect(jsonPath("$.eventId").value(99L));

        verify(toolRequestService, never()).storeResponse(any(), any(), any(), any(), any());
    }

    private String validCreatePayload() {
        return """
                {
                  "title": "Planning",
                  "startTime": "2026-03-10T10:00:00",
                  "endTime": "2026-03-10T11:00:00",
                  "confirmed": true
                }
                """;
    }
}
