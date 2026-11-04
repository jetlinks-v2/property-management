package org.jetlinks.pro.patrol.service.terms;

import org.jetlinks.pro.terms.SubTableTermFragmentBuilder;
import org.springframework.stereotype.Component;

/** 分类关系复用框架子表条件，包含停用行的 exists 用于判断旧字段迁移是否已经完成。 */
@Component
public class PatrolPointCategoryTermBuilder extends SubTableTermFragmentBuilder {
    public static final String TERM_TYPE = "patrol-point-category";
    public PatrolPointCategoryTermBuilder() { super(TERM_TYPE, "巡更点位分类关系"); }
    @Override protected String getSubTableColumn() { return "pointId"; }
    @Override protected String getSubTableName() { return "pt_point_category"; }
}
