package org.jarvis.lifetracker.service;

import org.jarvis.lifetracker.domain.TransactionType;
import org.jarvis.lifetracker.dto.ParsedTransactionDTO;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BankNotificationParserTest {

    private final BankNotificationParser parser = new BankNotificationParser();

    @Test
    void parseNullTextMarksEveryFieldMissingAndIsLowConfidence() {
        ParsedTransactionDTO result = parser.parse(null);

        assertThat(result.valid()).isFalse();
        assertThat(result.confidence()).isEqualTo("LOW");
        assertThat(result.needsReview()).isTrue();
        assertThat(result.amount()).isNull();
        assertThat(result.currency()).isNull();
        assertThat(result.merchant()).isNull();
        assertThat(result.type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(result.category()).isEqualTo("uncategorized");
        assertThat(result.cardMask()).isNull();
        assertThat(result.notes()).contains(
                "no amount found", "currency not recognised", "merchant not identified",
                "operation type assumed EXPENSE");
        assertThat(result.dedupKey()).matches("[0-9a-f]{16}");
        assertThat(result.storedId()).isNull();
    }

    @Test
    void parseDecimalAmountWithKnownCurrencyCodeAndMerchantIsHighConfidence() {
        ParsedTransactionDTO result = parser.parse("Platnosc 45.99 PLN Lidl Warszawa");

        assertThat(result.amount()).isEqualByComparingTo("45.99");
        assertThat(result.currency()).isEqualTo("PLN");
        assertThat(result.merchant()).isEqualTo("Lidl");
        assertThat(result.category()).isEqualTo("groceries");
        assertThat(result.type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(result.confidence()).isEqualTo("HIGH");
        assertThat(result.valid()).isTrue();
        assertThat(result.needsReview()).isFalse();
        assertThat(result.notes()).doesNotContain("operation type assumed EXPENSE");
    }

    @Test
    void parseIntegerAmountWithCurrencySymbolAndMerchant() {
        ParsedTransactionDTO result = parser.parse("Charged 50 zl Biedronka");

        assertThat(result.amount()).isEqualByComparingTo("50");
        assertThat(result.merchant()).isEqualTo("Biedronka");
        assertThat(result.category()).isEqualTo("groceries");
    }

    @Test
    void parseCurrencySymbolIsMappedToIsoCode() {
        ParsedTransactionDTO result = parser.parse("Charged 50 zł Biedronka");

        assertThat(result.currency()).isEqualTo("PLN");
    }

    @Test
    void parseThousandsSeparatorAmountAndShoppingMerchant() {
        ParsedTransactionDTO result = parser.parse("Payment 1.234,56 EUR Zalando");

        assertThat(result.amount()).isEqualByComparingTo("1234.56");
        assertThat(result.currency()).isEqualTo("EUR");
        assertThat(result.merchant()).isEqualTo("Zalando");
        assertThat(result.category()).isEqualTo("shopping");
    }

    @Test
    void parseWithoutKnownMerchantOrCurrencyCombinationIsMediumConfidence() {
        ParsedTransactionDTO result = parser.parse("Payment 20.50 USD");

        assertThat(result.amount()).isEqualByComparingTo("20.50");
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.confidence()).isEqualTo("MEDIUM");
        assertThat(result.valid()).isTrue();
        assertThat(result.needsReview()).isTrue();
    }

    @Test
    void parseWithNoCurrencyOrTypeWordsIsLowConfidenceWithNotes() {
        ParsedTransactionDTO result = parser.parse("something 12,34");

        assertThat(result.amount()).isEqualByComparingTo("12.34");
        assertThat(result.currency()).isNull();
        assertThat(result.confidence()).isEqualTo("LOW");
        assertThat(result.valid()).isFalse();
        assertThat(result.notes()).contains("currency not recognised", "merchant not identified",
                "operation type assumed EXPENSE");
    }

    @Test
    void parseZeroAmountIsRejectedAsNotPositive() {
        ParsedTransactionDTO result = parser.parse("Payment 0.00 USD");

        assertThat(result.amount()).isNull();
        assertThat(result.valid()).isFalse();
        assertThat(result.notes()).contains("amount must be positive");
    }

    @Test
    void parseDetectsIncomeKeywords() {
        ParsedTransactionDTO result = parser.parse("Wynagrodzenie wplynelo 5000.00 EUR");

        assertThat(result.type()).isEqualTo(TransactionType.INCOME);
    }

    @Test
    void parseExtractsMaskedCardSuffixFromEndingKeyword() {
        ParsedTransactionDTO result = parser.parse("Payment ending 1234 amount 10.00 USD");

        assertThat(result.cardMask()).isEqualTo("**** 1234");
    }

    @Test
    void parseExtractsMaskedCardSuffixFromAsteriskPattern() {
        ParsedTransactionDTO result = parser.parse("Payment **** 5678 for 15.00 EUR");

        assertThat(result.cardMask()).isEqualTo("**** 5678");
    }

    @Test
    void parseMasksFullCardNumberInRawMaskedText() {
        String raw = "Card 1234 5678 9012 3456 charged 10.00 EUR";
        ParsedTransactionDTO result = parser.parse(raw);

        assertThat(result.rawMasked()).contains("**** **** **** ****");
        assertThat(result.rawMasked()).doesNotContain("1234 5678 9012 3456");
    }

    @Test
    void parseCapitalisesLowercaseMerchantOccurrence() {
        ParsedTransactionDTO result = parser.parse("Charged 25.00 EUR uber ride");

        assertThat(result.merchant()).isEqualTo("Uber");
        assertThat(result.category()).isEqualTo("transport");
    }

    @Test
    void parseUncategorisedWhenNoMerchantMatches() {
        ParsedTransactionDTO result = parser.parse("Payment 20.50 USD");

        assertThat(result.category()).isEqualTo("uncategorized");
    }

    @Test
    void parseDedupKeyDiffersForDifferentTransactions() {
        ParsedTransactionDTO first = parser.parse("Platnosc 45.99 PLN Lidl Warszawa");
        ParsedTransactionDTO second = parser.parse("Platnosc 12.00 PLN Lidl Warszawa");

        assertThat(first.dedupKey()).isNotEqualTo(second.dedupKey());
    }

    @Test
    void parseDedupKeyIsStableAcrossWallClockHourBoundaryForIdenticalText() {
        String text = "Platnosc 45.99 PLN Lidl Warszawa";
        // Precompute the fixed clock values BEFORE opening the static mock: calling
        // LocalDateTime.of(...) while LocalDateTime is mocked would itself be a stubbed
        // static call and break the when(LocalDateTime::now) stubbing (UnfinishedStubbing).
        // CALLS_REAL_METHODS keeps every other LocalDateTime static intact so parse() runs normally.
        LocalDateTime beforeMidnight = LocalDateTime.of(2026, 7, 4, 23, 58);
        LocalDateTime afterMidnightNextHour = LocalDateTime.of(2026, 7, 5, 0, 2);

        String dedupJustBeforeMidnight;
        try (MockedStatic<LocalDateTime> mocked =
                     Mockito.mockStatic(LocalDateTime.class, Mockito.CALLS_REAL_METHODS)) {
            mocked.when(LocalDateTime::now).thenReturn(beforeMidnight);
            dedupJustBeforeMidnight = parser.parse(text).dedupKey();
        }

        String dedupJustAfterMidnightNextHour;
        try (MockedStatic<LocalDateTime> mocked =
                     Mockito.mockStatic(LocalDateTime.class, Mockito.CALLS_REAL_METHODS)) {
            mocked.when(LocalDateTime::now).thenReturn(afterMidnightNextHour);
            dedupJustAfterMidnightNextHour = parser.parse(text).dedupKey();
        }

        // Re-parsing the exact same notification text must dedupe to the same key even
        // when the wall-clock hour rolls over between the two calls (retry / re-import),
        // otherwise the (user_id, dedup_key) unique constraint is silently bypassed.
        assertThat(dedupJustAfterMidnightNextHour).isEqualTo(dedupJustBeforeMidnight);
    }

    @Test
    void parseBlankTextBehavesLikeNullText() {
        ParsedTransactionDTO result = parser.parse("   ");

        assertThat(result.amount()).isNull();
        assertThat(result.valid()).isFalse();
    }

    @Test
    void parseSetsOccurredAtToRecentTimestamp() {
        java.time.LocalDateTime before = java.time.LocalDateTime.now().minusSeconds(5);
        ParsedTransactionDTO result = parser.parse("Payment 20.50 USD");
        java.time.LocalDateTime after = java.time.LocalDateTime.now().plusSeconds(5);

        assertThat(result.occurredAt()).isAfter(before).isBefore(after);
    }
}
