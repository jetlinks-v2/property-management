package org.jetlinks.pro.property.finance.document;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.property.finance.calculation.ReceivableTaxCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** 同一规范来源共享额度；模拟票据仍必须满足真实金额、税额和时效约束。 */
class DocumentRulesTest {
    private final DocumentRules rules = new DocumentRules(new ReceivableTaxCalculator());

    @Test
    void receivableAndEveryPaymentAliasShareOneCanonicalSource() {
        var bill = rules.canonical("RECEIVABLE", "bill", null, null);
        assertEquals(bill, rules.canonical("RECEIVED", "allocation-a", "bill", "payment-a"));
        assertEquals(bill, rules.canonical("RECEIVED", "allocation-b", "bill", "payment-b"));
        assertEquals(bill, rules.canonical("TEMPORARY", "temporary", "bill", "payment-a"));
        assertThrows(BusinessException.class, () -> rules.assertDistinct(List.of(bill, bill)));
    }

    @Test
    void splitTendersOfTemporaryPaymentShareThePaymentRoot() {
        var root = rules.canonical("TEMP_PAYMENT", "payment", null, null);
        assertEquals(root, rules.canonical("RECEIVED", "cash-line", null, "payment"));
        assertEquals(root, rules.canonical("RECEIVED", "bank-line", null, "payment"));
        assertEquals(root, rules.canonical("TEMPORARY", "temporary", null, "payment"));
        assertNotEquals(root, rules.canonical("TEMP_PAYMENT", "other-payment", null, null));
        assertThrows(
                BusinessException.class,
                () -> rules.canonical("TEMPORARY", "temporary", null, null));
    }

    @Test
    void unknownOrIncompleteSourcesDoNotBecomeZeroBalanceOrAnotherSource() {
        assertThrows(BusinessException.class, () -> rules.canonical("unknown", "id", null, null));
        assertThrows(BusinessException.class, () -> rules.canonical(null, "id", null, null));
        assertThrows(BusinessException.class, () -> rules.canonical("RECEIVABLE", " ", null, null));
        assertThrows(
                BusinessException.class, () -> rules.canonical("RECEIVED", "line", null, null));
        assertThrows(BusinessException.class, () -> rules.available(null, money("0")));
        assertThrows(BusinessException.class, () -> rules.available(money("100"), null));
        assertThrows(
                BusinessException.class, () -> new DocumentRules.CanonicalSource("UNKNOWN", "id"));
    }

    @Test
    void invoiceAndReceiptUseTheSameOccupationAndNeverReturnNegativeAvailability() {
        assertEquals(money("25.49"), rules.available(money("100.00"), money("74.51")));
        assertEquals(money("0.00"), rules.available(money("100"), money("101")));
        assertEquals(money("0.00"), rules.available(money("0"), money("0")));
        assertThrows(BusinessException.class, () -> rules.available(money("-1"), money("0")));
        assertThrows(BusinessException.class, () -> rules.available(money("1"), money("-1")));
        assertThrows(BusinessException.class, () -> rules.amount("0.001", false));
        assertThrows(BusinessException.class, () -> rules.amount("1000000000000000000.00", false));
    }

    @Test
    void moneyUsesDecimalCentsBeyondJavascriptSafeIntegers() {
        assertEquals(
                money("9007199254740993.01"),
                rules.available(money("9007199254740993.02"), money("0.01")));
        assertEquals(money("0.00"), rules.amount("0", true));
        assertThrows(BusinessException.class, () -> rules.amount("0.00", false));
        assertThrows(BusinessException.class, () -> rules.amount("1e2", false));
    }

    @Test
    void naturalYearAndCustomDaysKeepOriginalBoundaryIncludingLeapYear() {
        var paid = LocalDateTime.parse("2024-02-29T12:00:00");
        assertTrue(
                rules.withinTime(
                        paid, LocalDateTime.parse("2024-12-31T23:59:59"), "NATURAL_YEAR", null));
        assertFalse(
                rules.withinTime(
                        paid, LocalDateTime.parse("2025-01-01T00:00:00"), "NATURAL_YEAR", null));
        assertTrue(
                rules.withinTime(
                        paid, LocalDateTime.parse("2024-03-01T12:00:00"), "CUSTOM_DAYS", 1));
        assertFalse(
                rules.withinTime(
                        paid,
                        LocalDateTime.parse("2024-03-01T12:00:00.000000001"),
                        "CUSTOM_DAYS",
                        1));
        assertTrue(
                rules.withinTime(
                        null, LocalDateTime.parse("2025-01-01T00:00:00"), "NATURAL_YEAR", null));
        assertFalse(rules.withinTime(paid, paid.minusNanos(1), "NATURAL_YEAR", null));
        assertThrows(BusinessException.class, () -> rules.withinTime(paid, paid, "CUSTOM_DAYS", 0));
        assertThrows(BusinessException.class, () -> rules.withinTime(paid, paid, "UNKNOWN", 1));
    }

    @Test
    void fullAndDifferentialTaxReuseSixDigitAccountingAndCentDocumentAmounts() {
        var full = rules.tax("100.00", "0.02", "FULL", "0.00");
        assertEquals(new BigDecimal("1.960784"), full.outputTax());
        assertEquals(money("1.96"), full.taxAmount());
        assertEquals(money("98.04"), full.netAmount());
        assertEquals(money("100.00"), full.grossAmount());
        var differential = rules.tax("100.00", "0.02", "DIFFERENTIAL", "20.00");
        assertEquals(new BigDecimal("1.568627"), differential.outputTax());
        assertEquals(money("1.57"), differential.taxAmount());
        assertEquals(money("98.43"), differential.netAmount());
        assertEquals(money("20.00"), differential.differenceAmount());
        assertEquals(money("0.00"), rules.tax("0.01", "0", "FULL", "0").taxAmount());
    }

    @Test
    void missingInvalidOrPercentTaxCannotBeSilentlyTurnedIntoZeroTax() {
        assertThrows(BusinessException.class, () -> rules.tax("100", null, "FULL", "0"));
        assertThrows(BusinessException.class, () -> rules.tax("100", "6", "FULL", "0"));
        assertThrows(BusinessException.class, () -> rules.tax("100", "-0.01", "FULL", "0"));
        assertThrows(BusinessException.class, () -> rules.tax("100", "0.1234567", "FULL", "0"));
        assertThrows(BusinessException.class, () -> rules.tax("100", "0.06", "DIFFERENTIAL", null));
        assertThrows(
                BusinessException.class, () -> rules.tax("100", "0.06", "DIFFERENTIAL", "100"));
        assertThrows(BusinessException.class, () -> rules.tax("100", "0.06", "FULL", "0.01"));
    }

    @Test
    void taxAndNetConserveGrossAcrossTinyAndLargeAmounts() {
        var random = new Random(20261105);
        for (int i = 0; i < 500; i++) {
            var gross = BigDecimal.valueOf(1 + random.nextInt(100000000), 2);
            var rate = BigDecimal.valueOf(random.nextInt(1000001), 6);
            var result = rules.tax(gross.toPlainString(), rate.toPlainString(), "FULL", "0");
            assertEquals(gross, result.netAmount().add(result.taxAmount()));
            assertTrue(result.taxAmount().signum() >= 0);
            assertTrue(result.netAmount().signum() >= 0);
        }
    }

    @Test
    void reopenLimitDoesNotAuthorizeInvalidCounts() {
        assertTrue(rules.canReopen(0, 1));
        assertFalse(rules.canReopen(1, 1));
        assertFalse(rules.canReopen(0, 0));
        assertTrue(rules.canReopen(5, null));
        assertThrows(BusinessException.class, () -> rules.canReopen(-1, 3));
        assertThrows(BusinessException.class, () -> rules.canReopen(0, -1));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
