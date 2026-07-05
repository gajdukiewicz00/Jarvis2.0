package org.jarvis.lifetracker.service;

import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.dto.SubscriptionDTO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Detects likely subscriptions in a user's expense history: charges that repeat with the same
 * merchant, a roughly stable amount, and a roughly monthly cadence.
 *
 * <p>Plain, stateless algorithm class (not a Spring bean) — {@link FinanceService} owns an
 * instance directly so it never has to be mocked out in service-layer tests; it is unit-tested
 * on its own in {@code SubscriptionDetectionServiceTest}.
 */
public class SubscriptionDetectionService {

    /** Minimum repeated charges before we call it a subscription rather than a coincidence. */
    static final int MIN_OCCURRENCES = 3;

    /** How much the amount may drift between charges and still count as "the same" price. */
    static final BigDecimal AMOUNT_TOLERANCE_RATIO = new BigDecimal("0.05");

    /** Acceptable day range between consecutive charges to call the cadence "monthly". */
    static final long MIN_INTERVAL_DAYS = 21;
    static final long MAX_INTERVAL_DAYS = 40;

    public List<SubscriptionDTO> detect(List<Expense> expenses) {
        Map<String, List<Expense>> byMerchant = new LinkedHashMap<>();
        for (Expense expense : expenses) {
            String merchant = expense.getMerchant();
            if (merchant == null || merchant.isBlank() || expense.getAmount() == null || expense.getOccurredAt() == null) {
                continue;
            }
            byMerchant.computeIfAbsent(merchant.trim().toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(expense);
        }

        List<SubscriptionDTO> subscriptions = new ArrayList<>();
        for (List<Expense> transactions : byMerchant.values()) {
            transactions.sort(Comparator.comparing(Expense::getOccurredAt));
            subscriptions.addAll(clusterByAmount(transactions));
        }
        return subscriptions;
    }

    private List<SubscriptionDTO> clusterByAmount(List<Expense> chronological) {
        List<SubscriptionDTO> results = new ArrayList<>();
        List<Expense> remaining = new ArrayList<>(chronological);

        while (!remaining.isEmpty()) {
            Expense seed = remaining.remove(0);
            List<Expense> cluster = new ArrayList<>();
            cluster.add(seed);
            BigDecimal runningTotal = seed.getAmount();

            Iterator<Expense> it = remaining.iterator();
            while (it.hasNext()) {
                Expense candidate = it.next();
                BigDecimal clusterAverage = runningTotal.divide(BigDecimal.valueOf(cluster.size()), 4, RoundingMode.HALF_UP);
                if (isWithinTolerance(clusterAverage, candidate.getAmount())) {
                    cluster.add(candidate);
                    runningTotal = runningTotal.add(candidate.getAmount());
                    it.remove();
                }
            }

            if (cluster.size() >= MIN_OCCURRENCES && hasMonthlyCadence(cluster)) {
                results.add(toSubscriptionDTO(seed.getMerchant(), cluster));
            }
        }
        return results;
    }

    private boolean isWithinTolerance(BigDecimal clusterAverage, BigDecimal candidateAmount) {
        if (clusterAverage.signum() == 0) {
            return candidateAmount.signum() == 0;
        }
        BigDecimal diff = clusterAverage.subtract(candidateAmount).abs();
        BigDecimal tolerance = clusterAverage.abs().multiply(AMOUNT_TOLERANCE_RATIO);
        return diff.compareTo(tolerance) <= 0;
    }

    private boolean hasMonthlyCadence(List<Expense> chronologicalCluster) {
        for (int i = 1; i < chronologicalCluster.size(); i++) {
            long days = Duration.between(
                    chronologicalCluster.get(i - 1).getOccurredAt(),
                    chronologicalCluster.get(i).getOccurredAt()).toDays();
            if (days < MIN_INTERVAL_DAYS || days > MAX_INTERVAL_DAYS) {
                return false;
            }
        }
        return true;
    }

    private SubscriptionDTO toSubscriptionDTO(String merchant, List<Expense> chronologicalCluster) {
        BigDecimal total = chronologicalCluster.stream().map(Expense::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = total.divide(BigDecimal.valueOf(chronologicalCluster.size()), 2, RoundingMode.HALF_UP);

        long totalIntervalDays = 0;
        for (int i = 1; i < chronologicalCluster.size(); i++) {
            totalIntervalDays += Duration.between(
                    chronologicalCluster.get(i - 1).getOccurredAt(),
                    chronologicalCluster.get(i).getOccurredAt()).toDays();
        }
        long averageIntervalDays = Math.round(totalIntervalDays / (double) (chronologicalCluster.size() - 1));

        Expense first = chronologicalCluster.get(0);
        Expense last = chronologicalCluster.get(chronologicalCluster.size() - 1);
        String currency = chronologicalCluster.stream()
                .map(Expense::getCurrency)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("EUR");
        String category = chronologicalCluster.stream()
                .map(Expense::getCategory)
                .filter(value -> value != null && !value.isBlank())
                .reduce((a, b) -> b)
                .orElse("uncategorized");

        return new SubscriptionDTO(
                merchant,
                category,
                average,
                currency,
                chronologicalCluster.size(),
                averageIntervalDays,
                first.getOccurredAt(),
                last.getOccurredAt(),
                last.getOccurredAt().plusDays(averageIntervalDays));
    }
}
