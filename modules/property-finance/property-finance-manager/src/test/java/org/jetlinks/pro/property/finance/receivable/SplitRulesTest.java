package org.jetlinks.pro.property.finance.receivable;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.pro.property.finance.calculation.*;
import org.jetlinks.pro.property.finance.service.ReceivableCalculationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

class SplitRulesTest {
    final ReceivableCalculationService calculation =
            new ReceivableCalculationService(
                    new ReceivableAmountCalculator(
                            new ChargeFormulaParser(),
                            new ChargeFormulaEvaluator(),
                            new BillingProrationService()),
                    new ReceivableLateFeeCalculator());
    final SplitRules rules = new SplitRules(calculation);

    @Test
    void activeBillExposesImplementedAdjustmentAndSplitActions() {
        var row = new AdjustmentRulesTest().bill();
        assertTrue(
                ReceivableView.of(row, false)
                        .getAllowedOperations()
                        .containsAll(java.util.Set.of("ADJUST", "SPLIT")));
    }

    @Test
    void legacyAccruedDaysRoundingLeavesTheRemainderWithSecondPart() {
        var row = new AdjustmentRulesTest().bill();
        row.setCalcStartDate("2026-09-01");
        row.setCalcEndDate("2026-09-30");
        row.setAccruedAsOfDate("2026-09-28");
        row.setActualReceivableAmount(new BigDecimal("1829.30"));
        var amounts = rules.amounts(row, LocalDate.of(2026, 9, 16), 0);
        assertEquals(new BigDecimal("1045.00"), amounts.first());
        assertEquals(new BigDecimal("784.30"), amounts.second());
        assertEquals(row.getActualReceivableAmount(), amounts.first().add(amounts.second()));
    }

    @Test
    void splitDateMustLeaveTwoAccruedPartsAndRoundedAmountsMustBePositive() {
        var row = new AdjustmentRulesTest().bill();
        assertThrows(
                RuntimeException.class, () -> rules.amounts(row, LocalDate.of(2024, 2, 15), 2));
        assertThrows(RuntimeException.class, () -> rules.amounts(row, LocalDate.of(2024, 2, 9), 2));
        row.setActualReceivableAmount(new BigDecimal("0.01"));
        assertThrows(
                RuntimeException.class, () -> rules.amounts(row, LocalDate.of(2024, 2, 12), 0));
    }

    @Test
    void splitKeepsAllAmountsAndFuturePrincipalAcrossLeapMonthAndRoundingPrecisions() {
        for (int scale = 0; scale <= 2; scale++) {
            var source = new AdjustmentRulesTest().bill();
            source.setAssessmentAmount(new BigDecimal("1.23"));
            source.setTemporaryDispatchAmount(new BigDecimal("2.34"));
            source.setFeeReductionAmount(new BigDecimal("0.11"));
            source.setAdjustmentAmount(new BigDecimal("0.29"));
            var standard =
                    org.jetlinks.pro.property.finance.setting.SettingRules.standard(
                            source.getSnapshotJson());
            calculation.calculate(
                    source,
                    standard,
                    LocalDate.of(2024, 2, 15),
                    org.jetlinks.pro.property.finance.setting.BillingTaskKind.ALL);
            var children =
                    rules.children(
                            source,
                            new SplitInput(
                                    source.getVersion(),
                                    LocalDate.of(2024, 2, 12),
                                    scale,
                                    "a",
                                    "b",
                                    "日期拆分"));
            assertEquals(
                    source.getActualReceivableAmount(),
                    children.stream()
                            .map(r -> r.getActualReceivableAmount())
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
            assertEquals("2024-02-12", children.get(0).getCalcEndDate());
            assertEquals("2024-02-13", children.get(1).getCalcStartDate());
            for (var date :
                    java.util.List.of(
                            LocalDate.of(2024, 2, 15),
                            LocalDate.of(2024, 2, 20),
                            LocalDate.of(2024, 2, 29))) {
                calculation.calculate(
                        source,
                        standard,
                        date,
                        org.jetlinks.pro.property.finance.setting.BillingTaskKind.ACCRUE);
                children.forEach(
                        child ->
                                calculation.calculate(
                                        child,
                                        standard,
                                        date,
                                        org.jetlinks.pro.property.finance.setting.BillingTaskKind
                                                .ACCRUE));
                assertEquals(
                        source.getActualReceivableAmount(),
                        children.stream()
                                .map(r -> r.getActualReceivableAmount())
                                .reduce(BigDecimal.ZERO, BigDecimal::add));
                assertEquals(
                        source.getContractReceivableAmount(),
                        children.stream()
                                .map(r -> r.getContractReceivableAmount())
                                .reduce(BigDecimal.ZERO, BigDecimal::add));
            }
        }
    }
}
