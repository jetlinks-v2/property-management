package org.jetlinks.pro.property.finance.receivable;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.pro.property.finance.payment.CashierInput;
import org.jetlinks.pro.property.finance.payment.PaymentRules;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

class PaymentRulesTest {
    final AdjustmentRulesTest fixture = new AdjustmentRulesTest();
    final PaymentRules rules = new PaymentRules(fixture.calculation);

    org.jetlinks.pro.property.finance.entity.ReceivableEntity approvedBill() {
        var row = fixture.bill();
        row.setReviewState("APPROVED");
        return row;
    }

    CashierInput input(String amount) {
        var input = new CashierInput();
        input.setCompanyId("company");
        input.setPayerName("家人代缴");
        input.setPaidAt("2024-02-15T10:20:30");
        input.setItems(List.of(new CashierInput.Item("bill", 2L, new BigDecimal(amount))));
        input.setTenders(List.of(new CashierInput.Tender("1", null, new BigDecimal(amount))));
        return input;
    }

    @Test
    void paymentUsesFreshFrozenSnapshotAndPartialPaymentLeavesExactDebt() {
        var original = approvedBill();
        original.setCalculatedAsOfDate("2024-02-15");
        var result = rules.prepare(List.of(original), input("10.00"), LocalDate.of(2024, 2, 16));
        assertEquals("29.76", result.rows().get(0).getActualReceivableAmount().toPlainString());
        assertEquals("19.76", result.rows().get(0).getArrears().toPlainString());
        assertEquals("10.00", result.rows().get(0).getPaidAmount().toPlainString());
        assertEquals("25.51", original.getArrears().toPlainString());
        assertNotEquals(
                result.token(),
                rules.prepare(List.of(original), input("10.00"), LocalDate.of(2024, 2, 17))
                        .token());
        assertThrows(
                RuntimeException.class,
                () -> rules.prepare(List.of(original), input("29.77"), LocalDate.of(2024, 2, 16)));
    }

    @Test
    void mixedTendersConsumeInInputOrderWithoutLosingCent() {
        var a = approvedBill();
        var b = approvedBill();
        b.setId("second");
        var input = input("10.00");
        input.setItems(
                List.of(
                        new CashierInput.Item("bill", 2L, new BigDecimal("10.01")),
                        new CashierInput.Item("second", 2L, new BigDecimal("12.00"))));
        input.setTenders(
                List.of(
                        new CashierInput.Tender("1", null, new BigDecimal("5.00")),
                        new CashierInput.Tender("3", "bank-record", new BigDecimal("17.01"))));
        var result = rules.prepare(List.of(a, b), input, LocalDate.of(2024, 2, 15));
        assertEquals(
                List.of("5.00", "5.01", "12.00"),
                result.allocations().stream().map(line -> line.amount().toPlainString()).toList());
        assertEquals("22.01", result.total());
        assertEquals(0, a.getPaidAmount().signum());
        assertThrows(
                RuntimeException.class,
                () -> rules.prepare(List.of(a, b), input("0.001"), LocalDate.of(2024, 2, 15)));
        input.setTenders(List.of(new CashierInput.Tender("1", null, new BigDecimal("22.00"))));
        assertThrows(
                RuntimeException.class,
                () -> rules.prepare(List.of(a, b), input, LocalDate.of(2024, 2, 15)));
    }

    @Test
    void versionsDuplicateIdsAndOccupiedOrFrozenBillsCannotBeCollected() {
        var row = approvedBill();
        var input = input("1.00");
        row.setVersion(3L);
        assertThrows(
                RuntimeException.class,
                () -> rules.prepare(List.of(row), input, LocalDate.of(2024, 2, 15)));
        row.setVersion(2L);
        row.setFrozen(true);
        assertThrows(
                RuntimeException.class,
                () -> rules.prepare(List.of(row), input, LocalDate.of(2024, 2, 15)));
        row.setFrozen(false);
        row.setPaymentOccupiedAmount(new BigDecimal("0.01"));
        assertThrows(
                RuntimeException.class,
                () -> rules.prepare(List.of(row), input, LocalDate.of(2024, 2, 15)));
        row.setPaymentOccupiedAmount(BigDecimal.ZERO);
        input.setItems(List.of(input.getItems().get(0), input.getItems().get(0)));
        assertThrows(
                RuntimeException.class,
                () -> rules.prepare(List.of(row), input, LocalDate.of(2024, 2, 15)));
    }

    @Test
    void financialMonthClosePreservesBookedAmountButDoesNotPreventCollectingHistoricalDebt() {
        var row = approvedBill();
        row.setFinancialClosed(true);
        var result = rules.prepare(List.of(row), input("10.00"), LocalDate.of(2024, 3, 15));
        assertEquals("25.51", result.rows().get(0).getActualReceivableAmount().toPlainString());
        assertEquals("15.51", result.rows().get(0).getArrears().toPlainString());
        var changed = input("9.99");
        assertNotEquals(
                PaymentRules.fingerprint(input("10.00")), PaymentRules.fingerprint(changed));
    }

    @Test
    void unapprovedBillsAndWrongCompanyCannotBeCollected() {
        var row = approvedBill();
        for (String state : new String[]{null, "PENDING", "REJECTED"}) {
            row.setReviewState(state);
            assertThrows(RuntimeException.class, () -> rules.prepare(List.of(row), input("1.00"), AdjustmentRulesTest.TODAY));
        }
        row.setReviewState("APPROVED");
        var snapshot = com.alibaba.fastjson.JSON.parseObject(row.getSnapshotJson());
        snapshot.getJSONObject("standard").put("companyId", "another-company");
        row.setSnapshotJson(snapshot.toJSONString());
        assertThrows(RuntimeException.class, () -> rules.prepare(List.of(row), input("1.00"), AdjustmentRulesTest.TODAY));
    }

    @Test
    void cashierReductionChangesPrincipalAndDebtAndIsBoundToConfirmation() {
        var row = approvedBill();
        var input = input("10.00");
        input.setReduction(new CashierInput.Reduction("AMOUNT", new BigDecimal("5.00"), "柜台减免"));
        var result = rules.prepare(List.of(row), input, AdjustmentRulesTest.TODAY);
        assertEquals("5.00", result.rows().get(0).getFeeReductionAmount().toPlainString());
        assertEquals("10.51", result.rows().get(0).getArrears().toPlainString());
        assertEquals("20.51", result.rows().get(0).getActualReceivableAmount().toPlainString());
        assertEquals(0, row.getFeeReductionAmount().signum());
        var fingerprint = PaymentRules.fingerprint(input);
        input.setReduction(new CashierInput.Reduction("AMOUNT", new BigDecimal("5.00"), "不同原因"));
        assertNotEquals(fingerprint, PaymentRules.fingerprint(input));
        assertNotEquals(result.token(), rules.prepare(List.of(row), input, AdjustmentRulesTest.TODAY).token());
        row.setFinancialClosed(true);
        assertThrows(RuntimeException.class, () -> rules.prepare(List.of(row), input, AdjustmentRulesTest.TODAY));
    }
}
