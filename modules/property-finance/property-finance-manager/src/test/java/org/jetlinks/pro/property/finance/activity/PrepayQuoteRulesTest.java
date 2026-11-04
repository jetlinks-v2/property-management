package org.jetlinks.pro.property.finance.activity;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.web.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** 保留旧充赠样例，并用极小金额、有效期和季度反例验证不能生成负分配。 */
class PrepayQuoteRulesTest {
    PrepayQuoteRules.Candidate candidate(String type, String id, String start, String amount) {
        var date = LocalDate.parse(start);
        return new PrepayQuoteRules.Candidate(
                type, id, date, date.withDayOfMonth(date.lengthOfMonth()), new BigDecimal(amount));
    }

    List<PrepayQuoteRules.Candidate> months(int count, String amount) {
        var rows = new ArrayList<PrepayQuoteRules.Candidate>();
        for (int i = 0; i < count; i++)
            rows.add(
                    candidate(
                            "FUTURE",
                            null,
                            LocalDate.of(2027, 1, 1).plusMonths(i).toString(),
                            amount));
        return rows;
    }

    @Test
    void legacySixPlusOneKeepsArrearsFirstAndOriginalTailCent() {
        var arrears =
                List.of(
                        candidate("ARREARS", "a", "2026-08-01", "100.00"),
                        candidate("ARREARS", "b", "2026-07-01", "100.00"));
        var quote = PrepayQuoteRules.calculate("MONTHLY", 6, 1, arrears, months(5, "100.00"));
        assertEquals(new BigDecimal("600.00"), quote.payAmount());
        assertEquals(new BigDecimal("700.00"), quote.originalAmount());
        assertEquals("b", quote.lines().get(0).period().receivableId());
        assertEquals(new BigDecimal("85.71"), quote.lines().get(0).paidAmount());
        assertEquals(new BigDecimal("14.29"), quote.lines().get(0).discountAmount());
        assertEquals(new BigDecimal("85.74"), quote.lines().get(6).paidAmount());
        assertEquals(new BigDecimal("14.26"), quote.lines().get(6).discountAmount());
    }

    @Test
    void variablePeriodsUseFirstPaidAmountsRatherThanOneFlatPrice() {
        var quote =
                PrepayQuoteRules.calculate(
                        "MONTHLY",
                        2,
                        1,
                        List.of(candidate("ARREARS", "a", "2026-12-01", "50.00")),
                        List.of(
                                candidate("FUTURE", null, "2027-01-01", "100.00"),
                                candidate("FUTURE", null, "2027-02-01", "150.00")));
        assertEquals(new BigDecimal("150.00"), quote.payAmount());
        assertEquals(
                List.of(new BigDecimal("25.00"), new BigDecimal("50.00"), new BigDecimal("75.00")),
                quote.lines().stream().map(PrepayQuoteRules.Line::paidAmount).toList());
    }

    @Test
    void tinyAmountsNeverMakeNegativeTailOrNegativeDiscount() {
        var quote = PrepayQuoteRules.calculate("MONTHLY", 2, 2, List.of(), months(4, "0.01"));
        assertEquals(new BigDecimal("0.02"), quote.payAmount());
        assertEquals(
                List.of(
                        new BigDecimal("0.01"),
                        new BigDecimal("0.01"),
                        new BigDecimal("0.00"),
                        new BigDecimal("0.00")),
                quote.lines().stream().map(PrepayQuoteRules.Line::paidAmount).toList());
        conserved(quote);
        // 独立四舍五入还可能让最后一期超过其原额，必须预留剩余容量。
        var rows = months(4, "0.01");
        var small = PrepayQuoteRules.calculate("MONTHLY", 1, 3, List.of(), rows);
        conserved(small);
    }

    @Test
    void variedCentWeightsConserveAllAmountsAcrossDeterministicInputs() {
        var random = new Random(1120);
        for (int round = 0; round < 250; round++) {
            var rows = months(20, "0.01");
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i);
                rows.set(
                        i,
                        new PrepayQuoteRules.Candidate(
                                row.type(),
                                row.receivableId(),
                                row.start(),
                                row.end(),
                                BigDecimal.valueOf(random.nextInt(1000) + 1, 2)));
            }
            conserved(
                    PrepayQuoteRules.calculate(
                            "MONTHLY", 1 + random.nextInt(19), 1, List.of(), rows));
        }
    }

    void conserved(PrepayQuoteRules.Quote quote) {
        assertEquals(
                quote.payAmount(),
                quote.lines().stream()
                        .map(PrepayQuoteRules.Line::paidAmount)
                        .reduce(new BigDecimal("0.00"), BigDecimal::add));
        assertEquals(
                quote.originalAmount().subtract(quote.payAmount()),
                quote.lines().stream()
                        .map(PrepayQuoteRules.Line::discountAmount)
                        .reduce(new BigDecimal("0.00"), BigDecimal::add));
        for (var line : quote.lines()) {
            assertTrue(line.paidAmount().signum() >= 0);
            assertTrue(line.discountAmount().signum() >= 0);
            assertEquals(
                    line.period().originalAmount().setScale(2),
                    line.paidAmount().add(line.discountAmount()));
        }
    }

    @Test
    void ordinaryPrepayHasNoGiftAndQuarterlyCountsPeriodsNotMonths() {
        var quote = PrepayQuoteRules.calculate("MONTHLY", 3, 0, List.of(), months(3, "123.45"));
        assertEquals(new BigDecimal("370.35"), quote.payAmount());
        assertTrue(quote.lines().stream().allMatch(line -> line.discountAmount().signum() == 0));
        var quarters =
                List.of(
                        new PrepayQuoteRules.Candidate(
                                "FUTURE",
                                null,
                                LocalDate.of(2027, 1, 1),
                                LocalDate.of(2027, 3, 31),
                                new BigDecimal("300.00")),
                        new PrepayQuoteRules.Candidate(
                                "FUTURE",
                                null,
                                LocalDate.of(2027, 4, 1),
                                LocalDate.of(2027, 6, 30),
                                new BigDecimal("300.00")));
        var quarterly = PrepayQuoteRules.calculate("QUARTERLY", 3, 3, List.of(), quarters);
        assertEquals(new BigDecimal("300.00"), quarterly.payAmount());
        assertEquals(2, quarterly.lines().size());
        assertThrows(
                BusinessException.class,
                () -> PrepayQuoteRules.calculate("QUARTERLY", 2, 3, List.of(), quarters));
    }

    @Test
    void insufficientDuplicateOverlappingOrInvalidCandidatesAreRejected() {
        assertThrows(
                BusinessException.class,
                () -> PrepayQuoteRules.calculate("MONTHLY", 6, 1, List.of(), months(6, "100.00")));
        var row = candidate("FUTURE", "duplicate", "2027-01-01", "100.00");
        assertThrows(
                BusinessException.class,
                () -> PrepayQuoteRules.calculate("MONTHLY", 1, 1, List.of(), List.of(row, row)));
        assertThrows(
                BusinessException.class,
                () ->
                        PrepayQuoteRules.calculate(
                                "MONTHLY",
                                1,
                                0,
                                List.of(),
                                List.of(candidate("FUTURE", null, "2027-01-01", "-1.00"))));
        assertThrows(
                BusinessException.class,
                () -> PrepayQuoteRules.calculate("MONTHLY", 1, 0, List.of(), months(1, "0.001")));
        assertThrows(
                BusinessException.class,
                () -> PrepayQuoteRules.calculate("MONTHLY", 1201, 0, List.of(), months(1, "1.00")));
        assertThrows(
                BusinessException.class,
                () -> PrepayQuoteRules.calculate("YEARLY", 1, 0, List.of(), months(1, "1.00")));
    }

    @Test
    void zeroPricedLastPeriodCannotAbsorbMoreThanItsOriginalAmount() {
        var rows = months(4, "0.01");
        var last = rows.get(3);
        rows.set(
                3,
                new PrepayQuoteRules.Candidate(
                        last.type(), null, last.start(), last.end(), new BigDecimal("0.00")));
        var quote = PrepayQuoteRules.calculate("MONTHLY", 1, 3, List.of(), rows);
        assertEquals(
                List.of(
                        new BigDecimal("0.00"),
                        new BigDecimal("0.00"),
                        new BigDecimal("0.01"),
                        new BigDecimal("0.00")),
                quote.lines().stream().map(PrepayQuoteRules.Line::paidAmount).toList());
        conserved(quote);
    }

    @Test
    void repeatedBillIdentityAcrossDistinctPeriodsAndInsufficientPeriodsHaveBusinessCodes() {
        var duplicate =
                assertThrows(
                        BusinessException.class,
                        () ->
                                PrepayQuoteRules.calculate(
                                        "MONTHLY",
                                        1,
                                        1,
                                        List.of(),
                                        List.of(
                                                candidate("FUTURE", "bill", "2027-01-01", "1.00"),
                                                candidate(
                                                        "FUTURE", "bill", "2027-02-01", "1.00"))));
        assertEquals(409, duplicate.getStatus());
        assertEquals("error.property_finance_prepay_period_conflict", duplicate.getMessage());
        var missing =
                assertThrows(
                        BusinessException.class,
                        () ->
                                PrepayQuoteRules.calculate(
                                        "MONTHLY", 2, 0, List.of(), months(1, "1.00")));
        assertEquals(409, missing.getStatus());
        assertEquals("error.property_finance_prepay_periods_insufficient", missing.getMessage());
    }

    @Test
    void maximumTierIsBoundedAndAmountsRemainExactAboveJavascriptIntegerRange() {
        var quote =
                PrepayQuoteRules.calculate("MONTHLY", 1200, 1200, List.of(), months(2400, "0.01"));
        assertEquals(2400, quote.lines().size());
        assertEquals(new BigDecimal("12.00"), quote.payAmount());
        conserved(quote);
        var large =
                PrepayQuoteRules.calculate(
                        "MONTHLY", 1, 1, List.of(), months(2, "90071992547409.93"));
        assertEquals(new BigDecimal("90071992547409.93"), large.payAmount());
        conserved(large);
        assertThrows(
                BusinessException.class,
                () ->
                        PrepayQuoteRules.calculate(
                                "MONTHLY", 1, 1, List.of(), months(2, "999999999999999999.99")));
    }

    @Test
    void plannerUsesExplicitPurchaseDayAndIncludesLeapMonth() {
        var first =
                PrepayQuoteRules.plan(
                        "MONTHLY", LocalDate.of(2024, 2, 1), LocalDate.of(2020, 1, 1), null, 2);
        assertEquals(LocalDate.of(2024, 2, 1), first.get(0).start());
        assertEquals(LocalDate.of(2024, 2, 29), first.get(0).end());
        var mid =
                PrepayQuoteRules.plan(
                        "MONTHLY", LocalDate.of(2024, 2, 2), LocalDate.of(2020, 1, 1), null, 2);
        assertEquals(LocalDate.of(2024, 3, 1), mid.get(0).start());
    }

    @Test
    void monthlyClipsEffectiveEdgesAndStopsAtEffectiveEnd() {
        var plan =
                PrepayQuoteRules.plan(
                        "MONTHLY",
                        LocalDate.of(2026, 11, 1),
                        LocalDate.of(2026, 12, 16),
                        LocalDate.of(2027, 1, 5),
                        12);
        assertEquals(
                List.of(
                        new PrepayQuoteRules.Window(
                                LocalDate.of(2026, 12, 16), LocalDate.of(2026, 12, 31)),
                        new PrepayQuoteRules.Window(
                                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 5))),
                plan);
    }

    @Test
    void quarterlySkipsPartialQuarterAndNeverTruncatesEffectiveQuarter() {
        var start = LocalDate.of(2026, 1, 1);
        var plan =
                PrepayQuoteRules.plan(
                        "QUARTERLY",
                        LocalDate.of(2026, 10, 2),
                        start,
                        LocalDate.of(2027, 4, 30),
                        6);
        assertEquals(
                List.of(
                        new PrepayQuoteRules.Window(
                                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31))),
                plan);
        var future =
                PrepayQuoteRules.plan(
                        "QUARTERLY", LocalDate.of(2026, 10, 1), LocalDate.of(2030, 2, 1), null, 3);
        assertEquals(LocalDate.of(2030, 4, 1), future.get(0).start());
    }

    @Test
    void plannerRejectsInvalidRangeOrUnboundedWork() {
        assertThrows(
                BusinessException.class,
                () ->
                        PrepayQuoteRules.plan(
                                "MONTHLY",
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2026, 1, 1),
                                null,
                                2401));
        assertThrows(
                BusinessException.class,
                () ->
                        PrepayQuoteRules.plan(
                                "QUARTERLY",
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2026, 1, 1),
                                null,
                                1));
        assertThrows(
                BusinessException.class,
                () ->
                        PrepayQuoteRules.plan(
                                "MONTHLY",
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2026, 2, 1),
                                LocalDate.of(2026, 1, 1),
                                1));
    }
}
