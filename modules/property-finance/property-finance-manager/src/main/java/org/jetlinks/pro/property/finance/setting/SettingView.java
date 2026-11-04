package org.jetlinks.pro.property.finance.setting;

import com.alibaba.fastjson.JSON;

import lombok.Getter;
import lombok.Setter;

import org.jetlinks.pro.property.finance.entity.HouseSettingEntity;
import org.jetlinks.pro.property.finance.standard.StandardView;
import org.springframework.beans.BeanUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 详情提供只读快照；原始存储JSON不直接作为接口字段。 */
@Getter
@Setter
public class SettingView extends HouseSettingEntity {
    private Map<String, Object> snapshot;
    private boolean billed;
    private List<String> customerRelationshipIds;
    private List<String> customerNames;

    public static SettingView of(HouseSettingEntity row, boolean billed) {
        var view = new SettingView();
        BeanUtils.copyProperties(row, view);
        var snapshot = JSON.parseObject(row.getSnapshotJson());
        snapshot.put(
                "standard",
                StandardView.of(SettingRules.standard(row.getSnapshotJson()), LocalDate.now()));
        var values = snapshot.getJSONObject("values");
        values.replaceAll((key, value) -> value.toString());
        view.setSnapshot(snapshot);
        var customers = SettingRules.customers(row.getSnapshotJson());
        view.setCustomerRelationshipIds(
                customers.isEmpty()
                        ? row.getCustomerRelationshipId() == null
                                ? List.of()
                                : List.of(row.getCustomerRelationshipId())
                        : customers.stream()
                                .map(
                                        customer ->
                                                Objects.toString(
                                                        customer.get("relationshipId"), ""))
                                .toList());
        view.setCustomerNames(
                customers.isEmpty()
                        ? row.getCustomerName() == null ? List.of() : List.of(row.getCustomerName())
                        : customers.stream()
                                .map(customer -> Objects.toString(customer.get("name"), ""))
                                .toList());
        snapshot.put("customers", customers);
        view.setBilled(billed);
        return view;
    }
}
