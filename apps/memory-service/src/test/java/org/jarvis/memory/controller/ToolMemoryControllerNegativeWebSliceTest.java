package org.jarvis.memory.controller;

import org.jarvis.memory.service.MemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ToolMemoryController.class)
@AutoConfigureMockMvc(addFilters = false)
class ToolMemoryControllerNegativeWebSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MemoryService memoryService;

    @Test
    void searchWithMissingQueryReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/tools/memory/search")
                        .requestAttr("toolUserId", "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.details[0].field").value("query"));

        verifyNoInteractions(memoryService);
    }

    @Test
    void searchWithUnknownFieldReturnsInvalidPayload() throws Exception {
        mockMvc.perform(post("/api/v1/tools/memory/search")
                        .requestAttr("toolUserId", "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "project notes",
                                  "rogue": "value"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_payload"))
                .andExpect(jsonPath("$.message", containsString("Unknown field: rogue")));

        verifyNoInteractions(memoryService);
    }

    @Test
    void searchWithNonPositiveTopKReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/tools/memory/search")
                        .requestAttr("toolUserId", "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "project notes",
                                  "topK": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.details[0].field").value("topK"));

        verifyNoInteractions(memoryService);
    }
}
