package org.jetlinks.pro.property.finance.setting;

import com.alibaba.fastjson.JSON;

import lombok.Getter;
import lombok.Setter;

import org.jetlinks.pro.property.finance.entity.HouseSettingEntity;
import org.jetlinks.pro.property.finance.standard.StandardView;
import org.springframework.beans.BeanUtils;

import java.time.LocalDate;
import java.util.Map;

/** 详情提供只读快照；原始存储JSON不直接作为接口字段。 */
@Getter
@Setter
public class SettingView extends HouseSettingEntity {
    private Map<String, Object> snapshot;
    private boolean billed;

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
        view.setBilled(billed);
        return view;
    }
}
