package org.jarvis.lifetracker.service;

import org.jarvis.lifetracker.domain.TransactionType;
import org.jarvis.lifetracker.dto.ParsedTransactionDTO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, LOCAL-ONLY bank push-notification parser (no external LLM).
 *
 * <p>Extracts amount / currency / merchant / type / card-suffix from a raw push,
 * validates the result, scores confidence, categorises by merchant, masks the
 * card, and computes a dedup key. Low-confidence drafts are flagged
 * {@code needsReview=true} so they land in a manual inbox instead of finances.
 */
@Component
public class BankNotificationParser {

    /** ISO code -> recognised. Symbols map to codes. */
    private static final Map<String, String> CURRENCY_SYMBOLS = Map.of(
            "zł", "PLN", "€", "EUR", "$", "USD", "£", "GBP", "₴", "UAH", "₽", "RUB", "Kč", "CZK");
    private static final List<String> CURRENCY_CODES = List.of(
            "PLN", "EUR", "USD", "GBP", "UAH", "RUB", "CZK", "CHF", "SEK", "NOK");

    /** Substring (lowercase) -> category. First match wins. */
    private static final Map<String, String> MERCHANT_CATEGORY = new LinkedHashMap<>();
    static {
        for (String m : List.of("lidl", "auchan", "biedronka", "carrefour", "żabka", "zabka", "kaufland", "tesco", "rossmann"))
            MERCHANT_CATEGORY.put(m, "groceries");
        for (String m : List.of("uber", "bolt", "taxi", "freenow", "mpk", "pkp", "intercity", "orlen", "bp ", "shell"))
            MERCHANT_CATEGORY.put(m, "transport");
        for (String m : List.of("netflix", "spotify", "youtube", "hbo", "disney", "patreon", "icloud", "google one"))
            MERCHANT_CATEGORY.put(m, "subscriptions");
        for (String m : List.of("mcdonald", "kfc", "burger", "restaurant", "restauracja", "pizza", "starbucks", "costa"))
            MERCHANT_CATEGORY.put(m, "dining");
        for (String m : List.of("amazon", "allegro", "aliexpress", "zalando", "media markt", "rtv"))
            MERCHANT_CATEGORY.put(m, "shopping");
        for (String m : List.of("apteka", "pharmacy", "medicover", "luxmed"))
            MERCHANT_CATEGORY.put(m, "health");
    }

    private static final Pattern AMOUNT = Pattern.compile("(\\d{1,3}(?:[ .]\\d{3})*|\\d+)[.,](\\d{2})");
    private static final Pattern AMOUNT_INT = Pattern.compile("(?<![\\d.,])(\\d{1,7})(?![\\d.,])");
    private static final Pattern CARD = Pattern.compile("(?:\\*+\\s?|x+\\s?|ending\\s|końc\\w*\\s|karty\\s\\D*?)(\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    private static final List<String> INCOME_WORDS = List.of("wpłyn", "wplyn", "zasilen", "przelew przych", "wynagrodz", "salary", "income", "received", "credit", "зачислен", "поступлен", "пополнен", "зарплат");
    private static final List<String> EXPENSE_WORDS = List.of("płatność", "platnosc", "obciąż", "obciaz", "payment", "charged", "debit", "spent", "wypłata", "оплата", "списан", "покупка");

    public ParsedTransactionDTO parse(String raw) {
        List<String> notes = new ArrayList<>();
        String text = raw == null ? "" : raw.trim();
        String lower = text.toLowerCase(Locale.ROOT);

        // --- amount ---
        BigDecimal amount = null;
        Matcher am = AMOUNT.matcher(text);
        if (am.find()) {
            String intPart = am.group(1).replaceAll("[ .]", "");
            amount = new BigDecimal(intPart + "." + am.group(2));
        } else {
            Matcher ai = AMOUNT_INT.matcher(text);
            if (ai.find()) amount = new BigDecimal(ai.group(1));
        }
        if (amount == null) notes.add("no amount found");
        else if (amount.signum() <= 0) { notes.add("amount must be positive"); amount = null; }

        // --- currency ---
        String currency = null;
        for (String code : CURRENCY_CODES) {
            if (lower.contains(code.toLowerCase(Locale.ROOT))) { currency = code; break; }
        }
        if (currency == null) {
            for (Map.Entry<String, String> e : CURRENCY_SYMBOLS.entrySet()) {
                if (text.contains(e.getKey())) { currency = e.getValue(); break; }
            }
        }
        if (currency == null) notes.add("currency not recognised");

        // --- type ---
        TransactionType type = TransactionType.EXPENSE;
        if (INCOME_WORDS.stream().anyMatch(lower::contains)) type = TransactionType.INCOME;
        else if (EXPENSE_WORDS.stream().noneMatch(lower::contains)) notes.add("operation type assumed EXPENSE");

        // --- card mask ---
        String cardMask = null;
        Matcher cm = CARD.matcher(text);
        if (cm.find()) cardMask = "**** " + cm.group(1);

        // --- merchant + category ---
        String merchant = extractMerchant(text, lower);
        String category = categorise(lower);
        if (merchant == null) notes.add("merchant not identified");

        // --- confidence (US-BANK-004) ---
        int score = 0;
        if (amount != null) score++;
        if (currency != null) score++;
        if (merchant != null) score++;
        String confidence = score >= 3 ? "HIGH" : score == 2 ? "MEDIUM" : "LOW";
        boolean valid = amount != null && currency != null;
        boolean needsReview = !"HIGH".equals(confidence) || !valid; // US-BANK-005

        // --- dedup key (US-BANK-006) ---
        // Derived purely from the notification content (never wall-clock time): re-parsing the
        // identical raw text must always yield the identical dedup key, regardless of when the
        // parse happens, so retries/re-imports of the same push notification are correctly
        // caught by the (user_id, dedup_key) unique constraint instead of bypassing it whenever
        // the wall-clock hour rolls over between attempts.
        String normalizedRaw = lower.replaceAll("\\s+", " ").trim();
        String dedupKey = sha16((amount == null ? "?" : amount.toPlainString())
                + "|" + (currency == null ? "?" : currency)
                + "|" + (merchant == null ? "?" : merchant.toLowerCase(Locale.ROOT))
                + "|" + normalizedRaw);

        // --- mask raw (US-BANK-009): never keep full card digits ---
        String rawMasked = text.replaceAll("\\b(\\d{4})[ -]?(\\d{4})[ -]?(\\d{4})[ -]?(\\d{4})\\b", "**** **** **** ****")
                .replaceAll("(?<=\\D)(\\d{6,})", "******");

        return new ParsedTransactionDTO(valid, confidence, needsReview, amount, currency, merchant,
                type, category, cardMask, dedupKey, LocalDateTime.now(), rawMasked, notes, null, null);
    }

    private String extractMerchant(String text, String lower) {
        // 1) known merchant present?
        for (String key : MERCHANT_CATEGORY.keySet()) {
            int i = lower.indexOf(key.trim());
            if (i >= 0) {
                String slice = text.substring(i, Math.min(text.length(), i + key.trim().length()));
                return capitalise(slice.trim());
            }
        }
        // 2) heuristic: trailing capitalised token after the amount/currency
        Matcher m = Pattern.compile("([A-ZŻŁ][\\p{L}&.\\- ]{2,30})\\s*$").matcher(text.trim());
        if (m.find()) {
            String cand = m.group(1).trim();
            // strip a leading currency code / generic words so we don't mistake "PLN wynagrodzenie" for a merchant
            for (String code : CURRENCY_CODES) {
                if (cand.toUpperCase(Locale.ROOT).startsWith(code + " ")) cand = cand.substring(code.length()).trim();
            }
            String low = cand.toLowerCase(Locale.ROOT);
            if (low.matches("(wynagrodzenie|salary|przelew|transfer|operacja|payment)\\b.*")) return null;
            if (cand.length() >= 3 && !CURRENCY_CODES.contains(cand.toUpperCase(Locale.ROOT))) return cand;
        }
        return null;
    }

    private String categorise(String lower) {
        for (Map.Entry<String, String> e : MERCHANT_CATEGORY.entrySet()) {
            if (lower.contains(e.getKey().trim())) return e.getValue();
        }
        return "uncategorized";
    }

    private static String capitalise(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String sha16(String in) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(in.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(in.hashCode());
        }
    }
}
