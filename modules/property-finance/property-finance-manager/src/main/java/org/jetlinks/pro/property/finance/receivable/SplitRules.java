package org.jetlinks.pro.property.finance.receivable;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import com.alibaba.fastjson.JSON;

import lombok.RequiredArgsConstructor;

import org.jetlinks.pro.property.finance.entity.ReceivableEntity;
import org.jetlinks.pro.property.finance.service.ReceivableCalculationService;
import org.jetlinks.pro.property.finance.setting.SettingRules;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 日期拆分保留旧日占比及末段取差；子账本金使用原冻结公式的累计区间差。 */
@Component
@RequiredArgsConstructor
public class SplitRules {
    private final ReceivableCalculationService calculation;

    public Amounts amounts(ReceivableEntity row, LocalDate split, Integer scale) {
        var start = LocalDate.parse(row.getCalcStartDate());
        var end = LocalDate.parse(row.getCalcEndDate());
        var accrued = LocalDate.parse(row.getAccruedAsOfDate());
        var through =
                "CLOSED".equals(row.getPeriodStatus()) || accrued.isAfter(end) ? end : accrued;
        if (split == null
                || split.isBefore(start)
                || !split.isBefore(through)
                || scale == null
                || scale < 0
                || scale > 2
                || row.getActualReceivableAmount().signum() <= 0) throw error("split_invalid", 400);
        var first =
                row.getActualReceivableAmount()
                        .multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(start, split) + 1))
                        .divide(
                                BigDecimal.valueOf(ChronoUnit.DAYS.between(start, through) + 1),
                                scale,
                                SettingRules.rounding(SettingRules.standard(row.getSnapshotJson())))
                        .setScale(2);
        var second = row.getActualReceivableAmount().subtract(first);
        if (first.signum() <= 0 || second.signum() <= 0) throw error("split_invalid", 400);
        return new Amounts(first, second);
    }

    public List<ReceivableEntity> children(ReceivableEntity source, SplitInput input) {
        var amounts = amounts(source, input.splitDate(), input.scale());
        var first =
                child(
                        source,
                        LocalDate.parse(source.getCalcStartDate()),
                        input.splitDate(),
                        amounts.first(),
                        true,
                        amounts);
        var second =
                child(
                        source,
                        input.splitDate().plusDays(1),
                        LocalDate.parse(source.getCalcEndDate()),
                        amounts.second(),
                        false,
                        amounts);
        return List.of(first, second);
    }

    private ReceivableEntity child(
            ReceivableEntity source,
            LocalDate start,
            LocalDate end,
            BigDecimal target,
            boolean first,
            Amounts amounts) {
        var row = new ReceivableEntity();
        BeanUtils.copyProperties(source, row);
        row.setId(null);
        row.setBillKey(null);
        row.setSourceReceivableId(source.getId());
        row.setCalcStartDate(start.toString());
        row.setCalcEndDate(end.toString());
        row.setDueDate(end.toString());
        row.setChargeLocked(false);
        row.setSplit(false);
        var snapshot = JSON.parseObject(source.getSnapshotJson());
        if (snapshot.getString("principalRootStart") == null)
            snapshot.put("principalRootStart", source.getCalcStartDate());
        row.setSnapshotJson(snapshot.toJSONString());
        var standard = SettingRules.standard(row.getSnapshotJson());
        var principal =
                calculation.principal(row, standard, LocalDate.parse(source.getAccruedAsOfDate()));
        row.setContractReceivableAmount(principal.getContractReceivableAmount());
        row.setAccruedReceivableAmount(
                "CLOSED".equals(source.getPeriodStatus())
                        ? principal.getContractReceivableAmount()
                        : principal.getAccruedReceivableAmount());
        row.setAssessmentAmount(
                part(
                        source.getAssessmentAmount(),
                        source.getActualReceivableAmount(),
                        amounts.first(),
                        first));
        row.setTemporaryDispatchAmount(
                part(
                        source.getTemporaryDispatchAmount(),
                        source.getActualReceivableAmount(),
                        amounts.first(),
                        first));
        row.setFeeReductionAmount(
                part(
                        source.getFeeReductionAmount(),
                        source.getActualReceivableAmount(),
                        amounts.first(),
                        first));
        row.setAccruedLateFeeAmount(
                part(
                        source.getAccruedLateFeeAmount(),
                        source.getActualReceivableAmount(),
                        amounts.first(),
                        first));
        row.setLateFeeReductionAmount(
                part(
                        source.getLateFeeReductionAmount(),
                        source.getActualReceivableAmount(),
                        amounts.first(),
                        first));
        row.setFinalLateFeeAmount(
                row.getAccruedLateFeeAmount().subtract(row.getLateFeeReductionAmount()));
        // 调整金额承担本金累计舍入与旧日期分配之间的补差；两子账补差相抵，所有费用总额不丢失。
        row.setAdjustmentAmount(
                target.subtract(row.getAccruedReceivableAmount())
                        .subtract(row.getAssessmentAmount())
                        .subtract(row.getTemporaryDispatchAmount())
                        .add(row.getFeeReductionAmount())
                        .subtract(row.getFinalLateFeeAmount()));
        row.setActualReceivableAmount(target);
        row.setArrears(target);
        return row;
    }

    private BigDecimal part(
            BigDecimal value, BigDecimal total, BigDecimal firstAmount, boolean first) {
        var allocated = value.multiply(firstAmount).divide(total, 2, RoundingMode.HALF_UP);
        return first ? allocated : value.subtract(allocated);
    }

    public record Amounts(BigDecimal first, BigDecimal second) {}
}
