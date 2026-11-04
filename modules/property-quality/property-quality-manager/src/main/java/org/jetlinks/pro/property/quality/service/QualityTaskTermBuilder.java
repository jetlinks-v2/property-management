package org.jetlinks.pro.property.quality.service;

import org.jetlinks.pro.terms.SubTableTermFragmentBuilder;
import org.springframework.stereotype.Component;

/** 报表检查项反向关联已注入资产权限的任务条件，避免把任务 ID 全量搬入内存。 */
@Component
public class QualityTaskTermBuilder extends SubTableTermFragmentBuilder {
    public static final String TYPE = "quality-task";
    public QualityTaskTermBuilder() { super(TYPE, "品质任务范围"); }
    protected String getSubTableColumn() { return "id"; }
    protected String getSubTableName() { return "pq_task"; }
}
