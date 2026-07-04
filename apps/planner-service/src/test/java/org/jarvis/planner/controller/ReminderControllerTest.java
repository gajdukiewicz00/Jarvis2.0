package org.jarvis.planner.controller;

import org.jarvis.planner.model.Reminder;
import org.jarvis.planner.service.ReminderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReminderController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReminderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReminderService reminderService;

    private TestingAuthenticationToken authenticatedUser(String userId) {
        return new TestingAuthenticationToken(userId, "n/a", "ROLE_USER");
    }

    @Test
    @DisplayName("GET /reminders returns active reminders for the authenticated user")
    void getRemindersReturnsActiveReminders() throws Exception {
        Reminder reminder = new Reminder();
        reminder.setId(1L);
        reminder.setUserId("user-1");
        reminder.setMessage("Call mom");
        when(reminderService.getActiveReminders("user-1")).thenReturn(List.of(reminder));

        mockMvc.perform(get("/api/v1/planner/reminders").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message").value("Call mom"));

        verify(reminderService).getActiveReminders("user-1");
    }

    @Test
    @DisplayName("GET /reminders/upcoming with default days window forwards a 7-day range")
    void getUpcomingRemindersWithDefaultDaysForwardsSevenDayRange() throws Exception {
        when(reminderService.getUpcomingReminders(eq("user-1"), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/planner/reminders/upcoming").principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        ArgumentCaptor<Instant> startCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> endCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(reminderService).getUpcomingReminders(eq("user-1"), startCaptor.capture(), endCaptor.capture());
        long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(startCaptor.getValue(), endCaptor.getValue());
        assertEquals(7, daysBetween);
    }

    @Test
    @DisplayName("GET /reminders/upcoming with explicit days forwards the requested range")
    void getUpcomingRemindersWithExplicitDaysForwardsRange() throws Exception {
        when(reminderService.getUpcomingReminders(eq("user-1"), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/planner/reminders/upcoming")
                        .principal(authenticatedUser("user-1"))
                        .param("days", "3"))
                .andExpect(status().isOk());

        ArgumentCaptor<Instant> startCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> endCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(reminderService).getUpcomingReminders(eq("user-1"), startCaptor.capture(), endCaptor.capture());
        long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(startCaptor.getValue(), endCaptor.getValue());
        assertEquals(3, daysBetween);
    }

    @Test
    @DisplayName("POST /reminders creates a reminder using the authenticated user, ignoring a forged body userId")
    void createReminderUsesAuthenticatedUser() throws Exception {
        Reminder created = new Reminder();
        created.setId(10L);
        created.setUserId("user-1");
        created.setMessage("Drink water");
        when(reminderService.createReminder(any(Reminder.class))).thenReturn(created);

        mockMvc.perform(post("/api/v1/planner/reminders")
                        .principal(authenticatedUser("user-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "forged-user",
                                  "message": "Drink water",
                                  "reminderTime": "2026-08-01T10:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.userId").value("user-1"));

        ArgumentCaptor<Reminder> captor = ArgumentCaptor.forClass(Reminder.class);
        verify(reminderService).createReminder(captor.capture());
        assertEquals("user-1", captor.getValue().getUserId());
        assertEquals("Drink water", captor.getValue().getMessage());
    }
}
