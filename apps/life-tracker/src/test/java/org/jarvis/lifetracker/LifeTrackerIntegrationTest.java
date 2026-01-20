package org.jarvis.lifetracker;

import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.domain.TimeRecord;
import org.jarvis.lifetracker.domain.CalendarEvent;
import org.jarvis.lifetracker.repository.ExpenseRepository;
import org.jarvis.lifetracker.repository.TimeRecordRepository;
import org.jarvis.lifetracker.repository.CalendarEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for life-tracker service using Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class LifeTrackerIntegrationTest {

    private static final String USER_ID = "test-user";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("jarvis_db_test")
            .withUsername("jarvis")
            .withPassword("jarvis123");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ExpenseRepository expenseRepository;

    @Autowired
    private TimeRecordRepository timeRecordRepository;

    @Autowired
    private CalendarEventRepository calendarEventRepository;

    @BeforeEach
    void setUp() {
        expenseRepository.deleteAll();
        timeRecordRepository.deleteAll();
        calendarEventRepository.deleteAll();
    }

    // =========================================================================
    // Expense Tests
    // =========================================================================

    @Test
    @DisplayName("GET /api/v1/life/finance/expenses - returns empty list when no expenses")
    void getExpenses_emptyList() throws Exception {
        mockMvc.perform(get("/api/v1/life/finance/expenses")
                        .header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/v1/life/finance/expenses - returns expenses")
    void getExpenses_returnsList() throws Exception {
        // Given
        Expense expense = new Expense();
        expense.setAmount(BigDecimal.valueOf(25.50));
        expense.setCurrency("EUR");
        expense.setCategory("FOOD");
        expense.setDescription("Lunch");
        expense.setUserId(USER_ID);
        expense.setOccurredAt(LocalDateTime.now());
        expenseRepository.save(expense);

        // When/Then
        mockMvc.perform(get("/api/v1/life/finance/expenses")
                        .header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].amount", is(25.50)))
                .andExpect(jsonPath("$[0].currency", is("EUR")))
                .andExpect(jsonPath("$[0].category", is("FOOD")))
                .andExpect(jsonPath("$[0].description", is("Lunch")));
    }

    @Test
    @DisplayName("POST /api/v1/life/finance/expense - creates expense")
    void createExpense_success() throws Exception {
        String requestBody = """
            {
                "amount": 100.50,
                "currency": "EUR",
                "category": "TRANSPORT",
                "description": "Taxi"
            }
            """;

        mockMvc.perform(post("/api/v1/life/finance/expense")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", USER_ID)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount", is(100.50)))
                .andExpect(jsonPath("$.currency", is("EUR")))
                .andExpect(jsonPath("$.category", is("TRANSPORT")))
                .andExpect(jsonPath("$.description", is("Taxi")))
                .andExpect(jsonPath("$.id", notNullValue()));

        // Verify in database
        assert expenseRepository.count() == 1;
    }

    @Test
    @DisplayName("POST /api/v1/life/finance/expense - defaults currency to EUR")
    void createExpense_defaultCurrency() throws Exception {
        String requestBody = """
            {
                "amount": 50.00,
                "category": "FOOD",
                "description": "Dinner"
            }
            """;

        mockMvc.perform(post("/api/v1/life/finance/expense")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", USER_ID)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency", is("EUR")));
    }

    // =========================================================================
    // Time Record Tests
    // =========================================================================

    @Test
    @DisplayName("GET /api/v1/life/time/records - returns empty list when no records")
    void getTimeRecords_emptyList() throws Exception {
        mockMvc.perform(get("/api/v1/life/time/records")
                        .header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/v1/life/time/records - returns time records")
    void getTimeRecords_returnsList() throws Exception {
        // Given
        TimeRecord record = new TimeRecord();
        record.setUserId(USER_ID);
        record.setActivity("Coding");
        record.setStartTime(LocalDateTime.of(2025, 12, 2, 9, 0));
        record.setEndTime(LocalDateTime.of(2025, 12, 2, 12, 0));
        record.setDurationSeconds(10800L); // 3 hours
        timeRecordRepository.save(record);

        // When/Then
        mockMvc.perform(get("/api/v1/life/time/records")
                        .header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].activity", is("Coding")))
                .andExpect(jsonPath("$[0].durationSeconds", is(10800)));
    }

    // =========================================================================
    // Calendar Event Tests
    // =========================================================================

    @Test
    @DisplayName("GET /api/v1/life/calendar/events - returns empty list when no events")
    void getCalendarEvents_emptyList() throws Exception {
        mockMvc.perform(get("/api/v1/life/calendar/events")
                        .header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/v1/life/calendar/events - returns calendar events")
    void getCalendarEvents_returnsList() throws Exception {
        // Given
        CalendarEvent event = new CalendarEvent();
        event.setUserId(USER_ID);
        event.setTitle("Meeting");
        event.setStartTime(LocalDateTime.of(2025, 12, 2, 14, 0));
        event.setEndTime(LocalDateTime.of(2025, 12, 2, 15, 0));
        event.setDescription("Team sync");
        calendarEventRepository.save(event);

        // When/Then
        mockMvc.perform(get("/api/v1/life/calendar/events")
                        .header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title", is("Meeting")))
                .andExpect(jsonPath("$[0].description", is("Team sync")));
    }

    // =========================================================================
    // Actuator Tests
    // =========================================================================

    @Test
    @DisplayName("GET /actuator/health - returns UP status")
    void actuatorHealth_returnsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }
}
