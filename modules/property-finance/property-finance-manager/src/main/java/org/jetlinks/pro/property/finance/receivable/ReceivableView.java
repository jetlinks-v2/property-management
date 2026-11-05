package org.jetlinks.pro.property.finance.receivable;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import lombok.Getter;
import lombok.Setter;

import org.jetlinks.pro.property.finance.entity.HouseSettingEntity;
import org.jetlinks.pro.property.finance.entity.ReceivableEntity;
import org.jetlinks.pro.property.finance.setting.SettingView;
import org.springframework.beans.BeanUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

/** 列表只返回正式金额与已落地操作；只读详情按冻结快照解析，不查询当前客户关系。 */
@Getter
@Setter
public class ReceivableView extends ReceivableEntity {
    private Set<String> allowedOperations;
    private Map<String, Object> snapshot;
    private java.util.List<String> customerNames;
    private java.util.List<String> customerIds;
    private String projectName;
    private String payStatus;
    private String collectionStatus;
    private Long collectionDays;
    private Long overdueDays;
    private Long overdueMonths;
    private boolean deletedFlag;
    private String taskName;

    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal price;

    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal usageValue;

    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal incomeExcludingTax;

    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal outputTaxAmount;

    public static ReceivableView of(ReceivableEntity row, boolean detail) {
        return of(row, detail, LocalDate.now());
    }

    /** 列表与导出共用同一业务日期、快照和支付口径，不在浏览器重复计算财务事实。 */
    public static ReceivableView of(ReceivableEntity row, boolean detail, LocalDate asOf) {
        var view = new ReceivableView();
        BeanUtils.copyProperties(row, view);
        view.setDeletedFlag(Boolean.TRUE.equals(row.getDeleted()));
        view.readDisplaySnapshot(row);
        view.readCollection(row, asOf);
        var names =
                org.jetlinks.pro.property.finance.setting.SettingRules.customers(
                                row.getSnapshotJson())
                        .stream()
                        .map(customer -> java.util.Objects.toString(customer.get("name"), ""))
                        .filter(name -> !name.isBlank())
                        .toList();
        view.setCustomerNames(
                names.isEmpty()
                        ? row.getCustomerName() == null
                                ? java.util.List.of()
                                : java.util.List.of(row.getCustomerName())
                        : names);
        // 收费对象取账单快照，不用当前同房客户替换历史缴款资格。
        var customerIds =
                org.jetlinks.pro.property.finance.setting.SettingRules.customers(
                                row.getSnapshotJson())
                        .stream()
                        .map(customer -> java.util.Objects.toString(customer.get("customerId"), ""))
                        .filter(id -> !id.isBlank())
                        .toList();
        view.setCustomerIds(
                customerIds.isEmpty() && row.getCustomerId() != null
                        ? java.util.List.of(row.getCustomerId())
                        : customerIds);
        var operations = new java.util.LinkedHashSet<>(ReceivablePolicy.allowed(row));
        operations.retainAll(
                Set.of("DETAIL", "LOG", "FREEZE", "UNFREEZE", "DELETE", "ADJUST", "SPLIT"));
        operations.addAll(ReceivablePolicy.reviewAllowed(row));
        view.setAllowedOperations(operations);
        if (detail) {
            var setting = new HouseSettingEntity();
            setting.setSnapshotJson(row.getSnapshotJson());
            view.setSnapshot(SettingView.of(setting, true).getSnapshot());
        }
        return view;
    }

    private void readDisplaySnapshot(ReceivableEntity row) {
        if (row.getSnapshotJson() == null || row.getSnapshotJson().isBlank()) return;
        var frozen = JSON.parseObject(row.getSnapshotJson());
        setTaskName(frozen.getString("taskName"));
        // 历史导入未提供单价/用量，不能拿导入时的当前标准冒充历史计费依据。
        if ("HISTORICAL_IMPORT".equals(row.getSourceType())) return;
        var standard = frozen.getJSONObject("standard");
        if (standard != null) setPrice(standard.getBigDecimal("price"));
        var values = frozen.getJSONObject("values");
        if (values != null)
            setUsageValue(
                    values.getBigDecimal(
                            values.containsKey("BILLING_AREA")
                                    ? "BILLING_AREA"
                                    : "BILLING_VOLUME"));
    }

    private void readCollection(ReceivableEntity row, LocalDate asOf) {
        boolean paid = row.getPaidAmount() != null && row.getPaidAmount().signum() > 0;
        boolean settled = row.getArrears() != null && row.getArrears().signum() <= 0;
        setPayStatus(settled ? "PAID" : paid ? "PARTIAL" : "UNPAID");
        var due = row.getDueDate() == null ? null : LocalDate.parse(row.getDueDate());
        var received = row.getPaidDate() == null ? null : LocalDate.parse(row.getPaidDate());
        // 部分回款仍继续计算逾期；结清后才停止在缴款日，避免沿用上次部分付款日。
        var end = settled && received != null && received.isBefore(asOf) ? received : asOf;
        if (due != null) {
            setOverdueDays(Math.max(0, ChronoUnit.DAYS.between(due, end)));
            if (settled && received != null)
                setCollectionDays(Math.max(0, ChronoUnit.DAYS.between(due, received)));
            if (row.getSnapshotJson() != null) {
                var standard = JSON.parseObject(row.getSnapshotJson()).getJSONObject("standard");
                if (standard != null
                        && Integer.valueOf(1).equals(standard.getInteger("delayRoundType"))) {
                    var start = due.plusDays(1);
                    long months =
                            Math.max(
                                    0,
                                    ChronoUnit.MONTHS.between(
                                            YearMonth.from(start), YearMonth.from(end)));
                    setOverdueMonths(
                            months > 0 && start.plusMonths(months).isAfter(end)
                                    ? months - 1
                                    : months);
                }
            }
        }
        setCollectionStatus(
                settled
                        ? "PAID"
                        : paid
                                ? "PARTIAL"
                                : due == null
                                        ? "PENDING"
                                        : asOf.isAfter(due)
                                                ? "OVERDUE"
                                                : asOf.isBefore(due) ? "NOT_DUE" : "PENDING");
    }
}
