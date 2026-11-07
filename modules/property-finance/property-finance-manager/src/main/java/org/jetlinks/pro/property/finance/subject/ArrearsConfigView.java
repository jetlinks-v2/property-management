package org.jetlinks.pro.property.finance.subject;

import lombok.Getter;
import lombok.Setter;

import org.hswebframework.web.bean.FastBeanCopier;
import org.jetlinks.pro.property.finance.entity.ArrearsConfigEntity;
import org.jetlinks.pro.property.finance.entity.ArrearsRuleEntity;

import java.util.List;

/** 欠费通知等级列表，保留主档字段并附带当前有效的阈值规则。 */
@Getter
@Setter
public class ArrearsConfigView extends ArrearsConfigEntity {
    private List<ArrearsRuleEntity> rules;

    public static ArrearsConfigView of(ArrearsConfigEntity config, List<ArrearsRuleEntity> rules) {
        var view = FastBeanCopier.copy(config, new ArrearsConfigView());
        view.setRules(rules);
        return view;
    }
}
