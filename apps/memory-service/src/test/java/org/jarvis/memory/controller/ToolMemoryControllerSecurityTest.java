package org.jarvis.memory.controller;

import jakarta.servlet.Filter;
import org.jarvis.common.security.ServiceJwtFilter;
import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.memory.config.SecurityConfig;
import org.jarvis.memory.dto.SearchRequest;
import org.jarvis.memory.dto.SearchResponse;
import org.jarvis.memory.service.MemoryService;
import org.jarvis.memory.tooling.ToolUserIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ToolMemoryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, ServiceJwtFilter.class, ServiceJwtProvider.class})
@TestPropertySource(properties = {
        "service.jwt.secret=test-service-secret-key-1234567890123456",
        "service.jwt.required=true"
})
class ToolMemoryControllerSecurityTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private ToolUserIdFilter toolUserIdFilter;

    @Autowired
    private ServiceJwtProvider serviceJwtProvider;

    @MockBean
    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(springSecurityFilterChain)
                .addFilter(toolUserIdFilter)
                .build();
    }

    @Test
    void searchWithoutValidJwtReturnsUnauthorizedOrForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/tools/memory/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"project notes\"}")
                        .header("Authorization", "Bearer invalid.jwt.token")
                        .header("X-User-Id", "forged-user"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .isIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value()));
    }

    @Test
    void searchWithValidServiceTokenButMissingUserHeaderReturnsBadRequest() throws Exception {
        String serviceToken = serviceJwtProvider.createToken("api-gateway", List.of("SVC_INTERNAL"));

        mockMvc.perform(post("/api/v1/tools/memory/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"project notes\"}")
                        .header("X-Service-Token", serviceToken))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(memoryService);
    }

    @Test
    void searchUsesDelegatedUserIdAndDefaultToolCorrelationId() throws Exception {
        String serviceToken = serviceJwtProvider.createToken("api-gateway", List.of("SVC_INTERNAL"));
        when(memoryService.search(any(SearchRequest.class), eq("tool-memory")))
                .thenReturn(SearchResponse.builder().chunks(List.of()).contextText("").estimatedTokens(0).build());

        mockMvc.perform(post("/api/v1/tools/memory/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"project notes\"}")
                        .header("X-Service-Token", serviceToken)
                        .header("X-User-Id", "user-123"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<SearchRequest> captor = org.mockito.ArgumentCaptor.forClass(SearchRequest.class);
        verify(memoryService).search(captor.capture(), eq("tool-memory"));
        assertEquals("user-123", captor.getValue().getUserId());
        assertEquals("project notes", captor.getValue().getQuery());
        assertEquals(5, captor.getValue().getTopK());
        assertEquals(600, captor.getValue().getMaxTokens());
    }
}
