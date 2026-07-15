package org.jarvis.voicegateway.client.impl;

import java.util.List;
import java.util.Map;

/**
 * Pure, side-effect-free builder for the spoken finance summary. Kept separate from the gateway
 * so the formatting (money rounding, top-category selection, ru/en wording) is unit-testable
 * without any HTTP stubs.
 */
final class FinanceVoiceSummary {

    private FinanceVoiceSummary() {}

    /**
     * @param monthExpense  total spend this month (nullable)
     * @param todayExpense  total spend today (nullable)
     * @param currency      currency code (nullable → RUB)
     * @param topCategories map of category → amount (nullable)
     */
    @SuppressWarnings("unchecked")
    static String build(boolean ru, Object monthExpense, Object todayExpense, String currency, Object topCategories) {
        String cur = currency != null && !currency.isBlank() ? currency : "RUB";
        Double month = asDouble(monthExpense);
        Double today = asDouble(todayExpense);
        String cats = topCategoriesText(topCategories instanceof Map ? (Map<String, Object>) topCategories : null);

        if (month == null && today == null) {
            return ru
                    ? "Сэр, по финансам пока нет данных за этот период."
                    : "Sir, there is no finance data for this period yet.";
        }

        StringBuilder sb = new StringBuilder();
        if (ru) {
            sb.append("Сэр, ");
            if (today != null) {
                sb.append("сегодня вы потратили ").append(money(today)).append(" ").append(cur).append(", ");
            }
            if (month != null) {
                sb.append("за месяц ").append(money(month)).append(" ").append(cur).append(".");
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
                sb.setLength(sb.length() - 2);
                sb.append(".");
            }
            if (cats != null) {
                sb.append(" Основные категории: ").append(cats).append(".");
            }
        } else {
            sb.append("Sir, ");
            if (today != null) {
                sb.append("you spent ").append(money(today)).append(" ").append(cur).append(" today, ");
            }
            if (month != null) {
                sb.append(money(month)).append(" ").append(cur).append(" this month.");
            }
            if (cats != null) {
                sb.append(" Top categories: ").append(cats).append(".");
            }
        }
        return sb.toString().trim();
    }

    /** Top 3 categories by amount, e.g. "продукты, транспорт, кафе". */
    private static String topCategoriesText(Map<String, Object> byCategory) {
        if (byCategory == null || byCategory.isEmpty()) {
            return null;
        }
        List<String> top = byCategory.entrySet().stream()
                .sorted((a, b) -> Double.compare(asDoubleOr0(b.getValue()), asDoubleOr0(a.getValue())))
                .limit(3)
                .map(Map.Entry::getKey)
                .filter(k -> k != null && !k.isBlank())
                .toList();
        return top.isEmpty() ? null : String.join(", ", top);
    }

    private static String money(double v) {
        // Whole numbers read cleaner; keep 2 decimals only when needed.
        if (v == Math.floor(v)) {
            return String.valueOf((long) v);
        }
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static Double asDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static double asDoubleOr0(Object value) {
        Double d = asDouble(value);
        return d != null ? d : 0.0;
    }
}
