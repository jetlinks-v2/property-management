package org.jetlinks.pro.property.finance.receivable;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.RequiredArgsConstructor;

import org.jetlinks.pro.property.finance.calculation.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.service.ReceivableCalculationService;
import org.jetlinks.pro.property.finance.setting.*;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.LocalDate;
import java.util.*;

/** 旧modern调整语义：公式产生合同额差值，附加金额累计，原计费快照和日期不变。 */
@Component
@RequiredArgsConstructor
public class AdjustmentRules {
    private final ReceivableCalculationService calculation;
    private final ReceivableAmountCalculator amounts;

    public Result calculate(ReceivableEntity original, AdjustmentInput input, LocalDate today) {
        validate(original, input, today);
        var asOf = input.getCalculationAsOfDate() == null ? today : input.getCalculationAsOfDate();
        var standard = SettingRules.standard(original.getSnapshotJson());
        FormulaChange formula = null;
        BigDecimal delta;
        if ("DIRECT".equals(input.getAdjustmentMode())) {
            delta = number(input.getDirectAmount());
            if (delta.signum() <= 0
                    || !Set.of("INCREASE", "DECREASE")
                            .contains(Objects.toString(input.getDirectDirection(), "")))
                throw error("adjustment_invalid", 400);
            if ("DECREASE".equals(input.getDirectDirection())) delta = delta.negate();
        } else if ("FORMULA".equals(input.getAdjustmentMode())) {
            formula = formula(original, standard, input.getFormulaLine());
            delta =
                    new BigDecimal(formula.afterAmount())
                            .subtract(new BigDecimal(formula.beforeAmount()));
        } else throw error("adjustment_invalid", 400);

        var assessment = number(input.getAssessmentAmount());
        int count =
                input.getTemporaryDispatchCount() == null ? 0 : input.getTemporaryDispatchCount();
        if (count < 0) throw error("adjustment_invalid", 400);
        var dispatch =
                number(input.getTemporaryDispatchUnitPrice()).multiply(BigDecimal.valueOf(count));
        var feeReduction = number(input.getFeeReductionAmount());
        var lateReduction = number(input.getLateFeeReductionAmount());
        if (lateReduction.signum() > 0
                && (input.getLateFeeReductionReason() == null
                        || input.getLateFeeReductionReason().isBlank()))
            throw error("adjustment_late_reason", 400);
        if (delta.signum() == 0
                && assessment.signum() == 0
                && dispatch.signum() == 0
                && feeReduction.signum() == 0
                && lateReduction.signum() == 0) throw error("adjustment_no_change", 400);
        var changed = copy(original);
        changed.setAdjustmentAmount(original.getAdjustmentAmount().add(delta));
        changed.setAssessmentAmount(original.getAssessmentAmount().add(assessment));
        changed.setTemporaryDispatchAmount(original.getTemporaryDispatchAmount().add(dispatch));
        changed.setFeeReductionAmount(original.getFeeReductionAmount().add(feeReduction));
        changed.setLateFeeReductionAmount(original.getLateFeeReductionAmount().add(lateReduction));
        // 旧发布完成后还会按当天刷新；预览同时给出截至日与实际写入日，避免确认金额与落账不同。
        var referenceBefore = compute(copy(original), standard, asOf);
        var referenceAfter = compute(copy(changed), standard, asOf);
        var effectiveBefore = compute(copy(original), standard, today);
        var effectiveAfter = compute(copy(changed), standard, today);
        var fingerprint =
                JSON.toJSONString(
                        List.of(
                                original.getId(),
                                original.getVersion(),
                                input,
                                today.toString(),
                                effectiveAfter));
        return new Result(
                effectiveAfter,
                delta,
                formula,
                referenceBefore,
                referenceAfter,
                effectiveBefore,
                effectiveAfter,
                asOf,
                today,
                hash(fingerprint));
    }

    private ReceivableEntity compute(
            ReceivableEntity row, StandardEntity standard, LocalDate date) {
        calculation.calculate(row, standard, date, BillingTaskKind.ALL);
        if (row.getLateFeeReductionAmount().compareTo(row.getAccruedLateFeeAmount()) > 0)
            throw error("adjustment_late_reduction_exceeded", 400);
        if (row.getActualReceivableAmount().compareTo(row.getPaidAmount()) < 0)
            throw error("adjustment_below_paid", 409);
        return row;
    }

    private FormulaChange formula(
            ReceivableEntity row, StandardEntity standard, AdjustmentInput.FormulaLine line) {
        if (line == null
                || !Objects.equals(line.getSubjectId(), row.getSubjectId())
                || !Objects.equals(line.getStandardId(), row.getStandardId()))
            throw error("adjustment_formula_scope", 400);
        var beforeStart = LocalDate.parse(row.getCalcStartDate());
        var beforeEnd = LocalDate.parse(row.getCalcEndDate());
        var afterStart =
                line.getAfterCalcStartDate() == null ? beforeStart : line.getAfterCalcStartDate();
        var afterEnd = line.getAfterCalcEndDate() == null ? beforeEnd : line.getAfterCalcEndDate();
        if (afterStart.isBefore(beforeStart)
                || afterEnd.isAfter(beforeEnd)
                || afterEnd.isBefore(afterStart)) throw error("adjustment_formula_dates", 400);
        var names = SettingRules.names(standard);
        var before = SettingRules.values(row.getSnapshotJson());
        var after = new LinkedHashMap<>(before);
        var submitted =
                line.getAfterVariableValues() == null
                        ? Map.<String, String>of()
                        : line.getAfterVariableValues();
        if (submitted.size() > 64) throw error("adjustment_invalid", 400);
        for (var entry : submitted.entrySet()) {
            if ("PERIOD_MONTHS".equals(entry.getKey())
                    || !names.containsKey(entry.getKey())
                    || entry.getValue() == null
                    || entry.getValue().isBlank()) throw error("adjustment_variable_invalid", 400);
            boolean nonNegative =
                    Set.of("STANDARD_PRICE", "BILLING_AREA", "IMPORTED_CONTRACT_AMOUNT")
                            .contains(entry.getKey());
            after.put(entry.getKey(), variableNumber(entry.getValue(), nonNegative));
        }
        // 使用原账单覆盖区间作边界，延续旧formulaAmount；不回读已被修改的标准价格或房屋面积。
        if (JSON.parseObject(row.getSnapshotJson()).getString("principalRootStart") != null) {
            var beforeAmount =
                    calculation
                            .principal(row, standard, before, beforeStart, beforeEnd, beforeEnd)
                            .getContractReceivableAmount();
            var afterAmount =
                    calculation
                            .principal(row, standard, after, afterStart, afterEnd, afterEnd)
                            .getContractReceivableAmount();
            return new FormulaChange(
                    strings(before),
                    strings(after),
                    beforeStart,
                    beforeEnd,
                    afterStart,
                    afterEnd,
                    beforeAmount.toPlainString(),
                    afterAmount.toPlainString());
        }
        var period = new BillingPeriod(beforeStart, beforeEnd);
        if (standard.getFormulaExpression().contains("${PERIOD_MONTHS}")) {
            var proration = new BillingProrationService();
            before.put("PERIOD_MONTHS", proration.monthFraction(beforeStart, beforeEnd));
            after.put("PERIOD_MONTHS", proration.monthFraction(afterStart, afterEnd));
        }
        var beforeAmount =
                amounts.calculate(
                                new ReceivableCalculationContext(
                                        period,
                                        beforeStart,
                                        beforeEnd,
                                        standard.getFormulaExpression(),
                                        before,
                                        names,
                                        standard.getChargeRoundType(),
                                        SettingRules.rounding(standard)),
                                beforeEnd)
                        .getContractReceivableAmount();
        var afterAmount =
                amounts.calculate(
                                new ReceivableCalculationContext(
                                        period,
                                        afterStart,
                                        afterEnd,
                                        standard.getFormulaExpression(),
                                        after,
                                        names,
                                        standard.getChargeRoundType(),
                                        SettingRules.rounding(standard)),
                                afterEnd)
                        .getContractReceivableAmount();
        return new FormulaChange(
                strings(before),
                strings(after),
                beforeStart,
                beforeEnd,
                afterStart,
                afterEnd,
                beforeAmount.toPlainString(),
                afterAmount.toPlainString());
    }

    private static Map<String, String> strings(Map<String, BigDecimal> values) {
        var result = new TreeMap<String, String>();
        values.forEach((key, value) -> result.put(key, value.toPlainString()));
        return result;
    }

    private static void validate(ReceivableEntity row, AdjustmentInput input, LocalDate today) {
        ReceivablePolicy.assertAllowed(row, "ADJUST");
        if (input == null || !Objects.equals(input.getVersion(), row.getVersion()))
            throw error("version_conflict", 409);
        if (input.getReason() == null
                || input.getReason().isBlank()
                || input.getReason().length() > 1000
                || input.getRemark() != null && input.getRemark().length() > 500
                || input.getLateFeeReductionReason() != null
                        && input.getLateFeeReductionReason().length() > 1000
                || input.getCalculationAsOfDate() != null
                        && input.getCalculationAsOfDate().isAfter(today)
                || today.isBefore(LocalDate.parse(row.getCalculatedAsOfDate())))
            throw error("adjustment_invalid", 400);
        var files = input.getAttachmentFileIds();
        if (files != null
                && (files.size() > 5
                        || new HashSet<>(files).size() != files.size()
                        || files.stream()
                                .anyMatch(id -> id == null || id.isBlank() || id.length() > 128)))
            throw error("adjustment_invalid", 400);
        var names = input.getAttachmentNames();
        if (names != null
                && (names.size() > 5
                        || names.entrySet().stream()
                                .anyMatch(
                                        entry ->
                                                files == null
                                                        || !files.contains(entry.getKey())
                                                        || entry.getValue() == null
                                                        || entry.getValue().isBlank()
                                                        || entry.getValue().length() > 255)))
            throw error("adjustment_invalid", 400);
    }

    private static BigDecimal number(String value) {
        if (value == null || value.isBlank()) return new BigDecimal("0.00");
        if (!value.trim().matches("[0-9]{1,18}(\\.[0-9]{1,2})?"))
            throw error("adjustment_invalid", 400);
        return new BigDecimal(value.trim()).setScale(2);
    }

    private static BigDecimal variableNumber(String value, boolean nonNegative) {
        if (!value.trim().matches("-?[0-9]{1,18}(\\.[0-9]{1,2})?"))
            throw error("adjustment_variable_invalid", 400);
        var number = new BigDecimal(value.trim());
        if (nonNegative && number.signum() < 0) throw error("adjustment_variable_invalid", 400);
        return number;
    }

    private static ReceivableEntity copy(ReceivableEntity row) {
        var copy = new ReceivableEntity();
        BeanUtils.copyProperties(row, copy);
        return copy;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record FormulaChange(
            Map<String, String> beforeVariables,
            Map<String, String> afterVariables,
            LocalDate beforeStart,
            LocalDate beforeEnd,
            LocalDate afterStart,
            LocalDate afterEnd,
            String beforeAmount,
            String afterAmount) {}

    public record Result(
            @JsonIgnore ReceivableEntity after,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(
                            using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
                    BigDecimal delta,
            FormulaChange formula,
            ReceivableEntity referenceBefore,
            ReceivableEntity referenceAfter,
            ReceivableEntity effectiveBefore,
            ReceivableEntity effectiveAfter,
            LocalDate calculationAsOfDate,
            LocalDate businessDate,
            String token) {}
}
