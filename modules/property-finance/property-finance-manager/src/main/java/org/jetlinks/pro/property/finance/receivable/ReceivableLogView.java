package org.jetlinks.pro.property.finance.receivable;

import com.alibaba.fastjson.JSON;

import lombok.Getter;
import lombok.Setter;

import org.jetlinks.pro.property.finance.entity.ReceivableLogEntity;
import org.springframework.beans.BeanUtils;

import java.util.Map;

/** 操作记录按结构化前后值展示，不把序列化存储字符串交给页面。 */
@Getter
@Setter
public class ReceivableLogView extends ReceivableLogEntity {
    private Map<String, Object> before;
    private Map<String, Object> after;

    public static ReceivableLogView of(ReceivableLogEntity row) {
        var view = new ReceivableLogView();
        BeanUtils.copyProperties(row, view);
        view.setBefore(JSON.parseObject(row.getBeforeJson()));
        view.setAfter(JSON.parseObject(row.getAfterJson()));
        return view;
    }
}
