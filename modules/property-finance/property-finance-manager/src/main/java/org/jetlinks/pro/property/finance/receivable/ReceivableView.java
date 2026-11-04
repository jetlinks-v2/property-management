package org.jetlinks.pro.property.finance.receivable;

import lombok.Getter;
import lombok.Setter;

import org.jetlinks.pro.property.finance.entity.HouseSettingEntity;
import org.jetlinks.pro.property.finance.entity.ReceivableEntity;
import org.jetlinks.pro.property.finance.setting.SettingView;
import org.springframework.beans.BeanUtils;

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

    public static ReceivableView of(ReceivableEntity row, boolean detail) {
        var view = new ReceivableView();
        BeanUtils.copyProperties(row, view);
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
        view.setAllowedOperations(operations);
        if (detail) {
            var setting = new HouseSettingEntity();
            setting.setSnapshotJson(row.getSnapshotJson());
            view.setSnapshot(SettingView.of(setting, true).getSnapshot());
        }
        return view;
    }
}
