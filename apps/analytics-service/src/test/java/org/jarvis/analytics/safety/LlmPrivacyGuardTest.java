package org.jarvis.analytics.safety;

import org.jarvis.analytics.dto.ExpenseDTO;
import org.jarvis.analytics.dto.WellnessLogDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmPrivacyGuardTest {

    @Test
    void isNotLocalAndDisallowsSensitiveDataByDefault() {
        LlmPrivacyGuard guard = new LlmPrivacyGuard(false, false);

        assertThat(guard.isLocal()).isFalse();
        assertThat(guard.allowsSensitiveData()).isFalse();
    }

    @Test
    void localProviderAllowsSensitiveData() {
        LlmPrivacyGuard guard = new LlmPrivacyGuard(true, false);

        assertThat(guard.isLocal()).isTrue();
        assertThat(guard.allowsSensitiveData()).isTrue();
    }

    @Test
    void explicitOverrideAllowsSensitiveDataWithoutBeingLocal() {
        LlmPrivacyGuard guard = new LlmPrivacyGuard(false, true);

        assertThat(guard.isLocal()).isFalse();
        assertThat(guard.allowsSensitiveData()).isTrue();
    }

    @Test
    void blocksRawExpenseDtoFromReachingNonLocalProvider() {
        LlmPrivacyGuard guard = new LlmPrivacyGuard(false, false);
        ExpenseDTO expense = new ExpenseDTO(1L, "user-1", new BigDecimal("42.50"), "EUR",
                "Food", "lunch", "EXPENSE", "Cafe", LocalDateTime.now());

        assertThatThrownBy(() -> guard.assertSafeForExternalLlm(expense))
                .isInstanceOf(LlmPrivacyGuard.SensitiveDataBlockedException.class);
    }

    @Test
    void blocksRawWellnessLogDtoNestedInsideACollection() {
        LlmPrivacyGuard guard = new LlmPrivacyGuard(false, false);
        List<WellnessLogDTO> logs = List.of(new WellnessLogDTO("SLEEP", 7.5, LocalDate.now()));

        assertThatThrownBy(() -> guard.assertSafeForExternalLlm(logs))
                .isInstanceOf(LlmPrivacyGuard.SensitiveDataBlockedException.class);
    }

    @Test
    void blocksRawSensitiveDataNestedInsideAMap() {
        LlmPrivacyGuard guard = new LlmPrivacyGuard(false, false);
        Map<String, Object> report = Map.of(
                "summary", "some derived text",
                "rawExpense", new ExpenseDTO(1L, "user-1", BigDecimal.TEN, "EUR", "Food", null, "EXPENSE", null,
                        LocalDateTime.now()));

        assertThatThrownBy(() -> guard.assertSafeForExternalLlm(report))
                .isInstanceOf(LlmPrivacyGuard.SensitiveDataBlockedException.class);
    }

    @Test
    void allowsAggregateStringsAndPlainMapsToPassThrough() {
        LlmPrivacyGuard guard = new LlmPrivacyGuard(false, false);
        Map<String, Object> report = Map.of("report", "Средний сон 7.5 ч, потрачено 700.");

        assertThatCode(() -> guard.assertSafeForExternalLlm(report)).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertSafeForExternalLlm("just a derived summary")).doesNotThrowAnyException();
    }

    @Test
    void allowsRawDataWhenProviderIsLocal() {
        LlmPrivacyGuard guard = new LlmPrivacyGuard(true, false);
        ExpenseDTO expense = new ExpenseDTO(1L, "user-1", BigDecimal.TEN, "EUR", "Food", null, "EXPENSE", null,
                LocalDateTime.now());

        assertThatCode(() -> guard.assertSafeForExternalLlm(expense)).doesNotThrowAnyException();
    }
}
