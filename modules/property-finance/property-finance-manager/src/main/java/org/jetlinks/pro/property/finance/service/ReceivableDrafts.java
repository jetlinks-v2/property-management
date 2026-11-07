package org.jetlinks.pro.property.finance.service;

import org.jetlinks.pro.property.finance.calculation.BillingPeriod;
import org.jetlinks.pro.property.finance.entity.*;
import org.jetlinks.pro.property.finance.setting.SettingRules;
import org.springframework.beans.BeanWrapperImpl;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 正式生成和预缴报价共用冻结账期草稿；构造不产生ID、审计时间或数据库写入。 */
final class ReceivableDrafts {
    private ReceivableDrafts() {}

    static ReceivableEntity create(
            HouseSettingEntity setting, BillingPeriod period, StandardEntity standard) {
        var row = new ReceivableEntity();
        // 正式账单继承已批准设置的结果，不再要求二次人工审核。
        row.setReviewState(setting.getReviewState());
        row.setReviewedBy(setting.getReviewedBy());
        row.setReviewedName(setting.getReviewedName());
        row.setReviewedOn(
                setting.getReviewedAt() == null
                        ? null
                        : java.time.Instant.ofEpochMilli(setting.getReviewedAt())
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDate()
                                .toString());
        row.setReviewReason(setting.getReviewReason());
        row.setSourceType("CHARGING");
        row.setBillKey(setting.getId() + ":" + period.getStart() + ":" + period.getEnd());
        row.setProjectId(setting.getProjectId());
        row.setSpaceId(setting.getSpaceId());
        row.setFullName(setting.getFullName());
        row.setSubjectId(setting.getSubjectId());
        row.setSubjectName(setting.getSubjectName());
        row.setStandardId(setting.getStandardId());
        row.setStandardName(setting.getStandardName());
        row.setSettingId(setting.getId());
        row.setConfigVersion(setting.getConfigVersion());
        row.setCustomerId(setting.getCustomerId());
        row.setCustomerRelationshipId(setting.getCustomerRelationshipId());
        row.setCustomerName(setting.getCustomerName());
        row.setPeriodStart(period.getStart().toString());
        row.setPeriodEnd(period.getEnd().toString());
        row.setCalcStartDate(
                period.coveredStart(LocalDate.parse(setting.getEffectiveStartDate())).toString());
        row.setCalcEndDate(
                period.coveredEnd(SettingRules.date(setting.getEffectiveEndDate(), false))
                        .toString());
        // 当前标准没有旧系统的应收月份/日配置，使用其默认规则：计费开始月末。
        row.setReceivableDate(
                LocalDate.parse(row.getCalcStartDate())
                        .with(java.time.temporal.TemporalAdjusters.lastDayOfMonth())
                        .toString());
        row.setDueDate(row.getCalcEndDate());
        row.setPeriodStatus("OPEN");
        row.setSnapshotJson(setting.getSnapshotJson());
        row.setMoneyScale(standard.getChargeRoundType());
        row.setFrozen(false);
        row.setPaymentLocked(false);
        row.setBankCollectionLocked(false);
        row.setLateFeeLocked(false);
        row.setFinancialClosed(false);
        row.setDiscountLocked(false);
        row.setChargeLocked(false);
        row.setSplit(false);
        var wrapper = new BeanWrapperImpl(row);
        for (var field : wrapper.getPropertyDescriptors())
            if (field.getPropertyType() == BigDecimal.class)
                wrapper.setPropertyValue(field.getName(), BigDecimal.ZERO);
        return row;
    }
}
