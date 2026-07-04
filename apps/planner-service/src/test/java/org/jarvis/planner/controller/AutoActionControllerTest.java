package org.jarvis.planner.controller;

import org.jarvis.planner.service.AutoActionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AutoActionController.class)
@AutoConfigureMockMvc(addFilters = false)
class AutoActionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AutoActionService autoActionService;

    private TestingAuthenticationToken authenticatedUser(String userId) {
        return new TestingAuthenticationToken(userId, "n/a", "ROLE_USER");
    }

    @Test
    @DisplayName("POST /actions/focus-mode with default mode triggers WORK focus mode")
    void triggerFocusModeWithDefaultModeTriggersWork() throws Exception {
        mockMvc.perform(post("/api/v1/planner/actions/focus-mode")
                        .principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("activated"))
                .andExpect(jsonPath("$.mode").value("WORK"));

        verify(autoActionService).triggerFocusMode("user-1", "WORK");
    }

    @Test
    @DisplayName("POST /actions/focus-mode with explicit mode forwards it")
    void triggerFocusModeWithExplicitModeForwardsIt() throws Exception {
        mockMvc.perform(post("/api/v1/planner/actions/focus-mode")
                        .principal(authenticatedUser("user-1"))
                        .param("mode", "RELAX"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("RELAX"));

        verify(autoActionService).triggerFocusMode("user-1", "RELAX");
    }

    @Test
    @DisplayName("POST /actions/music with default playlist starts WORK playlist")
    void startMusicWithDefaultPlaylistStartsWork() throws Exception {
        mockMvc.perform(post("/api/v1/planner/actions/music")
                        .principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("started"))
                .andExpect(jsonPath("$.playlist").value("WORK"));

        verify(autoActionService).startMusicPlaylist("user-1", "WORK");
    }

    @Test
    @DisplayName("POST /actions/music with explicit playlist forwards it")
    void startMusicWithExplicitPlaylistForwardsIt() throws Exception {
        mockMvc.perform(post("/api/v1/planner/actions/music")
                        .principal(authenticatedUser("user-1"))
                        .param("playlistType", "PARTY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playlist").value("PARTY"));

        verify(autoActionService).startMusicPlaylist("user-1", "PARTY");
    }

    @Test
    @DisplayName("POST /actions/pomodoro with default duration starts a 25 minute timer")
    void startPomodoroWithDefaultDurationStartsTwentyFiveMinutes() throws Exception {
        mockMvc.perform(post("/api/v1/planner/actions/pomodoro")
                        .principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("started"))
                .andExpect(jsonPath("$.durationMinutes").value(25));

        verify(autoActionService).startPomodoroTimer("user-1", 25);
    }

    @Test
    @DisplayName("POST /actions/pomodoro with explicit duration forwards it")
    void startPomodoroWithExplicitDurationForwardsIt() throws Exception {
        mockMvc.perform(post("/api/v1/planner/actions/pomodoro")
                        .principal(authenticatedUser("user-1"))
                        .param("duration", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.durationMinutes").value(50));

        verify(autoActionService).startPomodoroTimer("user-1", 50);
    }

    @Test
    @DisplayName("POST /actions/break suggests a break for the authenticated user")
    void suggestBreakSuggestsBreak() throws Exception {
        mockMvc.perform(post("/api/v1/planner/actions/break")
                        .principal(authenticatedUser("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("suggested"));

        verify(autoActionService).suggestBreak("user-1");
    }
}
