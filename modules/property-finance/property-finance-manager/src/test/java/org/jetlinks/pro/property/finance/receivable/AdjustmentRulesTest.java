package org.jetlinks.pro.property.finance.receivable;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.pro.property.finance.calculation.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.service.ReceivableCalculationService;
import org.jetlinks.pro.property.finance.setting.BillingTaskKind;
import org.jetlinks.pro.property.finance.setting.SettingRules;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanWrapperImpl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

class AdjustmentRulesTest {
    @Test
    void carryCanReduceOutstandingAfterPartialCollectionWhileOrdinaryAdjustmentStaysBlocked() {
        var original = bill();
        original.setPaidAmount(new BigDecimal("1.00"));
        original.setArrears(
                original.getActualReceivableAmount().subtract(original.getPaidAmount()));
        var input = new AdjustmentInput();
        input.setVersion(original.getVersion());
        input.setAdjustmentMode("REDUCTION");
        input.setFeeReductionAmount("10.00");
        input.setReason("预收结转减免");
        var result = rules.calculateReduction(original, input, TODAY);
        assertEquals(new BigDecimal("1.00"), result.after().getPaidAmount());
        assertEquals(
                new BigDecimal("10.00"),
                result.effectiveBefore().getArrears().subtract(result.after().getArrears()));
        input.setAdjustmentMode("DIRECT");
        input.setDirectDirection("INCREASE");
        input.setDirectAmount("1.00");
        assertThrows(RuntimeException.class, () -> rules.calculate(original, input, TODAY));
    }

    @Test
    void internalCarryReductionDoesNotInventADirectAmountOrChangeTheFormula() {
        var original = bill();
        var input = new AdjustmentInput();
        input.setVersion(original.getVersion());
        input.setAdjustmentMode("REDUCTION");
        input.setFeeReductionAmount("20.00");
        input.setReason("预收结转减免");
        var result = rules.calculateReduction(original, input, TODAY);
        assertEquals(0, result.delta().signum());
        assertEquals(original.getAdjustmentAmount(), result.after().getAdjustmentAmount());
        assertEquals(
                original.getContractReceivableAmount(),
                result.after().getContractReceivableAmount());
        assertEquals(new BigDecimal("20.00"), result.after().getFeeReductionAmount());
        assertEquals(
                new BigDecimal("20.00"),
                result.effectiveBefore().getArrears().subtract(result.after().getArrears()));
        assertThrows(RuntimeException.class, () -> rules.calculate(original, input, TODAY));
        input.setDirectAmount("0.01");
        assertThrows(
                RuntimeException.class, () -> rules.calculateReduction(original, input, TODAY));
    }

    @Test
    void internalCarryReductionRespectsBillLocksPaidFloorAndLateFeeLimit() {
        var original = bill();
        var input = new AdjustmentInput();
        input.setVersion(original.getVersion());
        input.setAdjustmentMode("REDUCTION");
        input.setFeeReductionAmount("20.00");
        input.setReason("预收结转减免");
        original.setFrozen(true);
        assertThrows(
                RuntimeException.class, () -> rules.calculateReduction(original, input, TODAY));
        original.setFrozen(false);
        input.setFeeReductionAmount("9999.00");
        assertThrows(
                RuntimeException.class, () -> rules.calculateReduction(original, input, TODAY));
        input.setFeeReductionAmount("0.00");
        input.setLateFeeReductionAmount("0.01");
        input.setLateFeeReductionReason("结转减免");
        assertThrows(
                RuntimeException.class, () -> rules.calculateReduction(original, input, TODAY));
    }

    static final LocalDate TODAY = LocalDate.of(2024, 2, 15);
    final ReceivableAmountCalculator amounts =
            new ReceivableAmountCalculator(
                    new ChargeFormulaParser(),
                    new ChargeFormulaEvaluator(),
                    new BillingProrationService());
    final ReceivableCalculationService calculation =
            new ReceivableCalculationService(amounts, new ReceivableLateFeeCalculator());
    final AdjustmentRules rules = new AdjustmentRules(calculation, amounts);

    ReceivableEntity bill() {
        var row = new ReceivableEntity();
        var wrapper = new BeanWrapperImpl(row);
        for (var field : wrapper.getPropertyDescriptors())
            if (field.getPropertyType() == BigDecimal.class)
                wrapper.setPropertyValue(field.getName(), BigDecimal.ZERO);
        row.setId("bill");
        row.setVersion(2L);
        row.setDeleted(false);
        row.setSubjectId("subject");
        row.setStandardId("standard");
        row.setPeriodStart("2024-02-01");
        row.setPeriodEnd("2024-02-29");
        row.setCalcStartDate("2024-02-10");
        row.setCalcEndDate("2024-02-29");
        row.setDueDate("2024-02-29");
        row.setPeriodStatus("OPEN");
        row.setMoneyScale(2);
        row.setSnapshotJson(
                "{\"standard\":{\"formulaExpression\":\"${STANDARD_PRICE}*${BILLING_AREA}*${PERIOD_MONTHS}\",\"chargeRoundType\":2,\"mantissa\":0,\"isDelay\":0,\"variablesSnapshotJson\":\"[{\\\"variableCode\\\":\\\"STANDARD_PRICE\\\",\\\"variableName\\\":\\\"单价\\\"},{\\\"variableCode\\\":\\\"BILLING_AREA\\\",\\\"variableName\\\":\\\"面积\\\"},{\\\"variableCode\\\":\\\"PERIOD_MONTHS\\\",\\\"variableName\\\":\\\"月数\\\"}]\"},\"values\":{\"STANDARD_PRICE\":\"1.23\",\"BILLING_AREA\":\"100.25\"}}");
        calculation.calculate(
                row, SettingRules.standard(row.getSnapshotJson()), TODAY, BillingTaskKind.ALL);
        return row;
    }

    AdjustmentInput input() {
        var input = new AdjustmentInput();
        input.setVersion(2L);
        input.setAdjustmentMode("DIRECT");
        input.setDirectDirection("INCREASE");
        input.setDirectAmount("10.00");
        input.setReason("核对账单");
        input.setCalculationAsOfDate(TODAY);
        return input;
    }

    @Test
    void directAndCommonChangesAreCumulativeAndNeverMutateSource() {
        var row = bill();
        var input = input();
        input.setAssessmentAmount("2.00");
        input.setTemporaryDispatchCount(2);
        input.setTemporaryDispatchUnitPrice("3.50");
        input.setFeeReductionAmount("1.00");
        var result = rules.calculate(row, input, TODAY);
        assertEquals("43.51", result.effectiveAfter().getActualReceivableAmount().toPlainString());
        assertEquals("10.00", result.after().getAdjustmentAmount().toPlainString());
        assertEquals("25.51", row.getActualReceivableAmount().toPlainString());
        assertEquals(row.getSnapshotJson(), result.after().getSnapshotJson());
        input.setDirectDirection("DECREASE");
        assertEquals(
                "23.51",
                rules.calculate(row, input, TODAY)
                        .effectiveAfter()
                        .getActualReceivableAmount()
                        .toPlainString());
    }

    @Test
    void modernFormulaAllowsExplicitAreaButKeepsOriginalCoverageAndValues() {
        var row = bill();
        var input = input();
        input.setAdjustmentMode("FORMULA");
        var line = new AdjustmentInput.FormulaLine();
        line.setSubjectId("subject");
        line.setStandardId("standard");
        line.setAfterVariableValues(Map.of("BILLING_AREA", "200.50"));
        input.setFormulaLine(line);
        var result = rules.calculate(row, input, TODAY);
        assertEquals("85.04", result.delta().toPlainString());
        assertEquals("110.55", result.effectiveAfter().getActualReceivableAmount().toPlainString());
        assertEquals(row.getSnapshotJson(), result.after().getSnapshotJson());
        assertEquals("2024-02-10", result.after().getCalcStartDate());
        line.setAfterVariableValues(Map.of("PERIOD_MONTHS", "1"));
        assertThrows(RuntimeException.class, () -> rules.calculate(row, input, TODAY));
        line.setAfterVariableValues(Map.of());
        line.setAfterCalcStartDate(LocalDate.of(2024, 2, 15));
        assertEquals("-21.26", rules.calculate(row, input, TODAY).delta().toPlainString());
        line.setAfterCalcStartDate(LocalDate.of(2024, 2, 9));
        assertThrows(RuntimeException.class, () -> rules.calculate(row, input, TODAY));
    }

    @Test
    void invalidAmountsNoChangeAndOverReductionAreRejected() {
        var row = bill();
        var input = input();
        input.setDirectAmount("0");
        assertThrows(RuntimeException.class, () -> rules.calculate(row, input, TODAY));
        input.setDirectAmount("1.001");
        assertThrows(RuntimeException.class, () -> rules.calculate(row, input, TODAY));
        input.setDirectAmount("10");
        input.setFeeReductionAmount("100");
        assertThrows(RuntimeException.class, () -> rules.calculate(row, input, TODAY));
        input.setFeeReductionAmount(null);
        input.setLateFeeReductionAmount("1");
        input.setLateFeeReductionReason("减免");
        assertThrows(RuntimeException.class, () -> rules.calculate(row, input, TODAY));
        input.setLateFeeReductionAmount(null);
        input.setCalculationAsOfDate(TODAY.plusDays(1));
        assertThrows(RuntimeException.class, () -> rules.calculate(row, input, TODAY));
    }

    @Test
    void earlierReferenceDateStillPreviewsExactlyWhatIsWrittenTodayAndTokenChanges() {
        var row = bill();
        var input = input();
        input.setCalculationAsOfDate(TODAY.minusDays(1));
        var result = rules.calculate(row, input, TODAY);
        assertEquals("31.26", result.referenceAfter().getActualReceivableAmount().toPlainString());
        assertEquals("35.51", result.effectiveAfter().getActualReceivableAmount().toPlainString());
        var oldToken = result.token();
        input.setRemark("补充备注");
        assertNotEquals(oldToken, rules.calculate(row, input, TODAY).token());
        assertNotEquals(oldToken, rules.calculate(row, input, TODAY.plusDays(1)).token());
    }
}
