package org.jetlinks.pro.property.finance.standard;

import com.alibaba.fastjson.JSON;

import lombok.Getter;
import lombok.Setter;

import org.jetlinks.pro.property.finance.entity.StandardEntity;
import org.springframework.beans.BeanUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 平铺规则与已冻结快照；有效状态明确以调用方业务日期计算。 */
@Getter
@Setter
public class StandardView extends StandardEntity {
    private String effectiveStatus;
    private List<VariableDefinition> variablesSnapshot;
    private Map<String, Object> taxSnapshot;

    public static StandardView of(StandardEntity entity, LocalDate date) {
        var view = new StandardView();
        BeanUtils.copyProperties(entity, view);
        view.setEffectiveStatus(StandardRules.effectiveStatus(entity, date));
        view.setVariablesSnapshot(StandardSnapshots.variables(entity.getVariablesSnapshotJson()));
        view.setTaxSnapshot(JSON.parseObject(entity.getTaxSnapshotJson()));
        return view;
    }
}
