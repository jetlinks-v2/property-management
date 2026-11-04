package org.jetlinks.pro.property.finance.service;

import lombok.RequiredArgsConstructor;

import org.jetlinks.pro.property.finance.calculation.*;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.setting.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** 无持久副作用的账单重算；自动任务、调整预览和正式写账复用同一金额与逾期入口。 */
@Component
@RequiredArgsConstructor
public class ReceivableCalculationService {
    private final ReceivableAmountCalculator calculator;
    private final ReceivableLateFeeCalculator lateFees;

    public void calculate(
            ReceivableEntity row, StandardEntity standard, LocalDate date, BillingTaskKind kind) {
        row.setCalculatedAsOfDate(date.toString());
        var principal = principal(row, standard, date);
        boolean closed =
                "CLOSED".equals(row.getPeriodStatus())
                        || kind.closePeriod()
                                && date.isAfter(LocalDate.parse(row.getCalcEndDate()));
        if (kind.accrue()) {
            row.setContractReceivableAmount(
                    SettingRules.money(principal.getContractReceivableAmount()));
            row.setAccruedReceivableAmount(
                    closed
                            ? row.getContractReceivableAmount()
                            : SettingRules.money(principal.getAccruedReceivableAmount()));
            row.setAccruedAsOfDate(date.toString());
            row.setPeriodStatus(closed ? "CLOSED" : "OPEN");
        }
        BigDecimal base =
                row.getAccruedReceivableAmount()
                        .add(row.getAdjustmentAmount())
                        .add(row.getAssessmentAmount())
                        .add(row.getTemporaryDispatchAmount())
                        .subtract(row.getFeeReductionAmount());
        SettingRules.money(base);
        if (kind.lateFee() && !Boolean.TRUE.equals(row.getLateFeeLocked())) {
            var late =
                    lateFees.calculateLateFee(
                            ReceivableSnapshot.builder()
                                    .receivableId(row.getId())
                                    .dueDate(LocalDate.parse(row.getDueDate()))
                                    .lateFeeEnabled(
                                            Integer.valueOf(1).equals(standard.getIsDelay()))
                                    .lateFeeCalculationType(
                                            Integer.valueOf(0).equals(standard.getDelayCalcForm())
                                                    ? "FIXED"
                                                    : "PERCENTAGE")
                                    .lateFeePeriodType(
                                            Integer.valueOf(0).equals(standard.getDelayRoundType())
                                                    ? "DAILY"
                                                    : "MONTHLY")
                                    .lateFeeBaseType(standard.getDelayBaseType())
                                    .lateFeeAmountOrRate(standard.getRateAmount())
                                    .currentReceivable(base)
                                    // 按当前未收本金计提，排除之前已计提滞纳金，保证同日重跑不会产生复利。
                                    .accumulatedOverdue(
                                            base.subtract(row.getPaidAmount()).max(BigDecimal.ZERO))
                                    .paid(false)
                                    .lateFeeLocked(false)
                                    .moneyScale(row.getMoneyScale())
                                    .build(),
                            date);
            row.setAccruedLateFeeAmount(SettingRules.money(late.getAccruedLateFee()));
        }
        row.setFinalLateFeeAmount(
                row.getAccruedLateFeeAmount()
                        .subtract(row.getLateFeeReductionAmount())
                        .max(BigDecimal.ZERO));
        row.setActualReceivableAmount(SettingRules.money(base.add(row.getFinalLateFeeAmount())));
        row.setArrears(
                row.getActualReceivableAmount().subtract(row.getPaidAmount()).max(BigDecimal.ZERO));
    }

    public ReceivableAmountSnapshot principal(
            ReceivableEntity row, StandardEntity standard, LocalDate date) {
        return principal(
                row,
                standard,
                SettingRules.values(row.getSnapshotJson()),
                LocalDate.parse(row.getCalcStartDate()),
                LocalDate.parse(row.getCalcEndDate()),
                date);
    }

    /** 累计金额相减使相邻拆分区间合计严格等于原账，元/角/分及非线性公式均不重复舍入。 */
    public ReceivableAmountSnapshot principal(
            ReceivableEntity row,
            StandardEntity standard,
            Map<String, BigDecimal> values,
            LocalDate start,
            LocalDate end,
            LocalDate date) {
        var rootText =
                com.alibaba
                        .fastjson
                        .JSON
                        .parseObject(row.getSnapshotJson())
                        .getString("principalRootStart");
        var root = rootText == null ? start : LocalDate.parse(rootText);
        var period =
                new BillingPeriod(
                        LocalDate.parse(row.getPeriodStart()), LocalDate.parse(row.getPeriodEnd()));
        var through =
                calculator.calculate(
                        new ReceivableCalculationContext(
                                period,
                                root,
                                end,
                                standard.getFormulaExpression(),
                                values,
                                SettingRules.names(standard),
                                standard.getChargeRoundType(),
                                SettingRules.rounding(standard)),
                        date);
        if (root.equals(start)) return through;
        var prior =
                calculator.calculate(
                        new ReceivableCalculationContext(
                                period,
                                root,
                                start.minusDays(1),
                                standard.getFormulaExpression(),
                                values,
                                SettingRules.names(standard),
                                standard.getChargeRoundType(),
                                SettingRules.rounding(standard)),
                        date);
        return new ReceivableAmountSnapshot(
                through.getContractReceivableAmount().subtract(prior.getContractReceivableAmount()),
                through.getAccruedReceivableAmount().subtract(prior.getAccruedReceivableAmount()),
                date);
    }
}
