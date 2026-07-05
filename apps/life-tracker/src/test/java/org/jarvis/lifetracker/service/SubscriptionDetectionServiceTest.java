package org.jarvis.lifetracker.service;

import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.domain.TransactionType;
import org.jarvis.lifetracker.dto.SubscriptionDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionDetectionServiceTest {

    private final SubscriptionDetectionService service = new SubscriptionDetectionService();

    private Expense expense(String merchant, String category, BigDecimal amount, String currency, LocalDateTime occurredAt) {
        Expense e = new Expense();
        e.setMerchant(merchant);
        e.setCategory(category);
        e.setAmount(amount);
        e.setCurrency(currency);
        e.setType(TransactionType.EXPENSE);
        e.setOccurredAt(occurredAt);
        return e;
    }

    @Test
    void detectsSubscriptionWithThreeStableMonthlyCharges() {
        List<Expense> expenses = List.of(
                expense("Netflix", "subscriptions", new BigDecimal("9.99"), "EUR",
                        LocalDateTime.of(2026, 1, 5, 10, 0)),
                expense("Netflix", "subscriptions", new BigDecimal("9.99"), "EUR",
                        LocalDateTime.of(2026, 2, 4, 10, 0)),
                expense("Netflix", "subscriptions", new BigDecimal("9.99"), "EUR",
                        LocalDateTime.of(2026, 3, 6, 10, 0)));

        List<SubscriptionDTO> result = service.detect(expenses);

        assertThat(result).hasSize(1);
        SubscriptionDTO subscription = result.get(0);
        assertThat(subscription.getMerchant()).isEqualTo("Netflix");
        assertThat(subscription.getCategory()).isEqualTo("subscriptions");
        assertThat(subscription.getCurrency()).isEqualTo("EUR");
        assertThat(subscription.getAverageAmount()).isEqualByComparingTo("9.99");
        assertThat(subscription.getOccurrences()).isEqualTo(3);
        assertThat(subscription.getFirstChargedAt()).isEqualTo(LocalDateTime.of(2026, 1, 5, 10, 0));
        assertThat(subscription.getLastChargedAt()).isEqualTo(LocalDateTime.of(2026, 3, 6, 10, 0));
        assertThat(subscription.getNextExpectedChargeAt()).isAfter(subscription.getLastChargedAt());
    }

    @Test
    void toleratesSmallAmountDriftBetweenCharges() {
        List<Expense> expenses = List.of(
                expense("Spotify", "subscriptions", new BigDecimal("9.99"), "EUR",
                        LocalDateTime.of(2026, 1, 1, 0, 0)),
                expense("Spotify", "subscriptions", new BigDecimal("10.19"), "EUR",
                        LocalDateTime.of(2026, 1, 31, 0, 0)),
                expense("Spotify", "subscriptions", new BigDecimal("9.99"), "EUR",
                        LocalDateTime.of(2026, 3, 2, 0, 0)));

        List<SubscriptionDTO> result = service.detect(expenses);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOccurrences()).isEqualTo(3);
    }

    @Test
    void doesNotFlagWhenFewerThanThreeOccurrences() {
        List<Expense> expenses = List.of(
                expense("Netflix", "subscriptions", new BigDecimal("9.99"), "EUR",
                        LocalDateTime.of(2026, 1, 5, 0, 0)),
                expense("Netflix", "subscriptions", new BigDecimal("9.99"), "EUR",
                        LocalDateTime.of(2026, 2, 4, 0, 0)));

        List<SubscriptionDTO> result = service.detect(expenses);

        assertThat(result).isEmpty();
    }

    @Test
    void doesNotFlagWeeklyChargesAsMonthlySubscription() {
        List<Expense> expenses = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            expenses.add(expense("Uber", "transport", new BigDecimal("15.00"), "EUR",
                    LocalDateTime.of(2026, 1, 1, 0, 0).plusDays(7L * i)));
        }

        List<SubscriptionDTO> result = service.detect(expenses);

        assertThat(result).isEmpty();
    }

    @Test
    void doesNotFlagWhenAmountVariesTooMuchBetweenCharges() {
        List<Expense> expenses = List.of(
                expense("Amazon", "shopping", new BigDecimal("10.00"), "EUR",
                        LocalDateTime.of(2026, 1, 1, 0, 0)),
                expense("Amazon", "shopping", new BigDecimal("40.00"), "EUR",
                        LocalDateTime.of(2026, 2, 1, 0, 0)),
                expense("Amazon", "shopping", new BigDecimal("90.00"), "EUR",
                        LocalDateTime.of(2026, 3, 1, 0, 0)));

        List<SubscriptionDTO> result = service.detect(expenses);

        assertThat(result).isEmpty();
    }

    @Test
    void ignoresExpensesWithoutAMerchant() {
        List<Expense> expenses = List.of(
                expense(null, "misc", new BigDecimal("9.99"), "EUR", LocalDateTime.of(2026, 1, 1, 0, 0)),
                expense("", "misc", new BigDecimal("9.99"), "EUR", LocalDateTime.of(2026, 2, 1, 0, 0)));

        List<SubscriptionDTO> result = service.detect(expenses);

        assertThat(result).isEmpty();
    }

    @Test
    void detectsMultipleIndependentSubscriptionsAcrossMerchants() {
        List<Expense> expenses = List.of(
                expense("Netflix", "subscriptions", new BigDecimal("9.99"), "EUR",
                        LocalDateTime.of(2026, 1, 5, 0, 0)),
                expense("Netflix", "subscriptions", new BigDecimal("9.99"), "EUR",
                        LocalDateTime.of(2026, 2, 4, 0, 0)),
                expense("Netflix", "subscriptions", new BigDecimal("9.99"), "EUR",
                        LocalDateTime.of(2026, 3, 6, 0, 0)),
                expense("Spotify", "subscriptions", new BigDecimal("5.99"), "EUR",
                        LocalDateTime.of(2026, 1, 10, 0, 0)),
                expense("Spotify", "subscriptions", new BigDecimal("5.99"), "EUR",
                        LocalDateTime.of(2026, 2, 9, 0, 0)),
                expense("Spotify", "subscriptions", new BigDecimal("5.99"), "EUR",
                        LocalDateTime.of(2026, 3, 11, 0, 0)));

        List<SubscriptionDTO> result = service.detect(expenses);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(SubscriptionDTO::getMerchant).containsExactlyInAnyOrder("Netflix", "Spotify");
    }
}
