package org.jetlinks.pro.property.finance.receivable;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.payment.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

class RefundRulesTest {
    static final LocalDate DATE = LocalDate.of(2024, 2, 15);

    RefundInput input(String amount) {
        var input = new RefundInput();
        input.setItems(List.of(new RefundInput.Item("a", 0L), new RefundInput.Item("b", 0L)));
        input.setAmount(new BigDecimal(amount));
        input.setCompanyId("company");
        input.setMethod("1");
        input.setRefundedAt("2024-02-15T10:00:00");
        input.setReceiverName("实际收款人");
        input.setReason("多收退回");
        return input;
    }

    PaymentAllocationEntity line(String id, String amount, String refunded) {
        var row = new PaymentAllocationEntity();
        row.setId(id);
        row.setVersion(0L);
        row.setAmount(new BigDecimal(amount));
        row.setRefundedAmount(new BigDecimal(refunded));
        return row;
    }

    @Test
    void apportionsRemainingRefundableCentsExactlyWithoutOverdrawingPartiallyRefundedSource() {
        var sources = List.of(line("a", "10.00", "9.99"), line("b", "10.00", "0"));
        var result = RefundRules.allocate(input("10.01"), sources, DATE);
        assertEquals(
                List.of(new BigDecimal("0.01"), new BigDecimal("10.00")),
                result.stream().map(RefundRules.Share::amount).toList());
        var cents =
                RefundRules.allocate(
                        input("0.01"),
                        List.of(line("a", "1.00", "0"), line("b", "1.00", "0")),
                        DATE);
        assertEquals(
                new BigDecimal("0.01"),
                cents.stream()
                        .map(RefundRules.Share::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals("a", cents.get(0).sourceId());
    }

    @Test
    void keepsLargeAmountsPreciseAndRejectsExcessDuplicateStaleOrInvalidRequests() {
        var sources =
                List.of(
                        line("a", "99999999999999999.99", "0"),
                        line("b", "99999999999999999.99", "0"));
        var result = RefundRules.allocate(input("199999999999999999.98"), sources, DATE);
        assertEquals(
                new BigDecimal("199999999999999999.98"),
                result.stream()
                        .map(RefundRules.Share::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertThrows(
                RuntimeException.class,
                () -> RefundRules.allocate(input("200000000000000000"), sources, DATE));
        var invalid = input("1.00");
        invalid.setItems(List.of(new RefundInput.Item("a", 0L), new RefundInput.Item("a", 0L)));
        assertThrows(RuntimeException.class, () -> RefundRules.allocate(invalid, sources, DATE));
        invalid.setItems(List.of(new RefundInput.Item("a", 1L)));
        assertThrows(RuntimeException.class, () -> RefundRules.allocate(invalid, sources, DATE));
        invalid.setItems(List.of(new RefundInput.Item("a", 0L)));
        invalid.setRefundedAt("2024-02-16T10:00:00");
        assertThrows(RuntimeException.class, () -> RefundRules.allocate(invalid, sources, DATE));
    }

    @Test
    void fingerprintIncludesAmountsSourcesAndAttachments() {
        var input = input("1.00");
        var original = RefundRules.fingerprint(input);
        input.setAmount(new BigDecimal("1.01"));
        assertNotEquals(original, RefundRules.fingerprint(input));
        input.setAmount(new BigDecimal("1.00"));
        assertEquals(original, RefundRules.fingerprint(input));
        input.setAttachmentFileId("file");
        input.setAttachmentName("凭证.pdf");
        assertNotEquals(original, RefundRules.fingerprint(input));
    }
}
