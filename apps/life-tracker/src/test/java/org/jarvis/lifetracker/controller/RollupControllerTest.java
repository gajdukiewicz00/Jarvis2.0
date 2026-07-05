package org.jarvis.lifetracker.controller;

import org.jarvis.lifetracker.dto.RollupDTO;
import org.jarvis.lifetracker.service.RollupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RollupController.class)
@AutoConfigureMockMvc(addFilters = false)
class RollupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RollupService rollupService;

    private RollupDTO rollup(String period, LocalDate start, LocalDate end) {
        return new RollupDTO(period, start, end, new BigDecimal("100.00"), new BigDecimal("40.00"), "EUR",
                Map.of(), Map.of(), 0.5, 3, 2);
    }

    @Test
    void weekWithoutUserIdReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/life/rollup/week"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void weekDelegatesToRollupServiceWithGivenDate() throws Exception {
        LocalDate date = LocalDate.of(2026, 3, 11);
        when(rollupService.weeklyRollup(eq("user-1"), eq(date)))
                .thenReturn(rollup("WEEK", LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 15)));

        mockMvc.perform(get("/api/v1/life/rollup/week")
                        .header("X-User-Id", "user-1")
                        .param("date", "2026-03-11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("WEEK"))
                .andExpect(jsonPath("$.startDate").value("2026-03-09"));
    }

    @Test
    void weekWithoutDateDefaultsToNullAnchor() throws Exception {
        when(rollupService.weeklyRollup(eq("user-1"), isNull()))
                .thenReturn(rollup("WEEK", LocalDate.now(), LocalDate.now().plusDays(6)));

        mockMvc.perform(get("/api/v1/life/rollup/week").header("X-User-Id", "user-1"))
                .andExpect(status().isOk());

        verify(rollupService).weeklyRollup("user-1", null);
    }

    @Test
    void monthDelegatesToRollupServiceWithParsedYearMonth() throws Exception {
        when(rollupService.monthlyRollup(eq("user-1"), eq(YearMonth.of(2026, 3))))
                .thenReturn(rollup("MONTH", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)));

        mockMvc.perform(get("/api/v1/life/rollup/month")
                        .header("X-User-Id", "user-1")
                        .param("month", "2026-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("MONTH"));
    }

    @Test
    void monthWithoutParamDefaultsToNullMonth() throws Exception {
        when(rollupService.monthlyRollup(eq("user-1"), any())).thenReturn(
                rollup("MONTH", LocalDate.now().withDayOfMonth(1), LocalDate.now()));

        mockMvc.perform(get("/api/v1/life/rollup/month").header("X-User-Id", "user-1"))
                .andExpect(status().isOk());

        verify(rollupService).monthlyRollup("user-1", null);
    }
}
